// Windows-only companion. It owns an invisible window, not a second audio player.
// API contract: https://learn.microsoft.com/windows/win32/api/systemmediatransportcontrolsinterop/nn-systemmediatransportcontrolsinterop-isystemmediatransportcontrolsinterop
using System;
using System.Globalization;
using System.Runtime.InteropServices;
using System.Runtime.InteropServices.WindowsRuntime;
using System.Text;
using System.Threading;
using System.Windows.Forms;
using Windows.Media;

[ComImport, Guid("ddb0472d-c911-4a1f-86d9-dc3d71a95f5a"), InterfaceType(ComInterfaceType.InterfaceIsIInspectable)]
interface IMediaInterop {
    SystemMediaTransportControls GetForWindow(IntPtr window, ref Guid iid);
}

static class MediaHost {
    static readonly object outputLock = new object();
    static SystemMediaTransportControls controls;
    static volatile bool applying;
    [DllImport("shell32.dll", CharSet = CharSet.Unicode)]
    static extern int SetCurrentProcessExplicitAppUserModelID(string id);
    static void Send(string command) { lock (outputLock) { Console.WriteLine(command); Console.Out.Flush(); } }
    static string Decode(string value) { return Encoding.UTF8.GetString(Convert.FromBase64String(value)); }
    static double Number(string value) { double n = double.Parse(value, CultureInfo.InvariantCulture); return double.IsNaN(n) || double.IsInfinity(n) ? 0 : Math.Max(0, n); }
    static void Apply(string line) {
        var p = line.Split('\t'); if (p.Length != 9 || p[0] != "STATE") return;
        applying = true;
        try {
            var status = (MediaPlaybackStatus)Enum.Parse(typeof(MediaPlaybackStatus), p[1]);
            double total = Number(p[3]), position = Math.Min(total, Number(p[2]));
            controls.IsEnabled = p[6].Length > 0;
            controls.PlaybackStatus = status;
            controls.ShuffleEnabled = p[4] == "1";
            controls.AutoRepeatMode = p[5] == "1" ? MediaPlaybackAutoRepeatMode.Track : MediaPlaybackAutoRepeatMode.None;
            var updater = controls.DisplayUpdater; updater.Type = MediaPlaybackType.Music;
            updater.MusicProperties.Title = Decode(p[6]); updater.MusicProperties.Artist = Decode(p[7]); updater.MusicProperties.AlbumTitle = Decode(p[8]); updater.Update();
            controls.UpdateTimelineProperties(new SystemMediaTransportControlsTimelineProperties {
                StartTime = TimeSpan.Zero, EndTime = TimeSpan.FromSeconds(total), MinSeekTime = TimeSpan.Zero,
                MaxSeekTime = TimeSpan.FromSeconds(total), Position = TimeSpan.FromSeconds(position)
            });
        } finally { applying = false; }
    }
    [STAThread]
    static int Main(string[] args) {
        // Compile/protocol checks may use this flag; it never registers a window/media session.
        if (args.Length == 1 && args[0] == "--protocol-check") {
            try {
                string line; while ((line = Console.ReadLine()) != null) {
                    var p = line.Split('\t'); if (p.Length != 9 || p[0] != "STATE") return 2;
                    Enum.Parse(typeof(MediaPlaybackStatus), p[1]); Number(p[2]); Number(p[3]);
                    Send("OK\t" + Convert.ToBase64String(Encoding.UTF8.GetBytes(Decode(p[6]))));
                } return 0;
            } catch { return 2; }
        }
        try {
            SetCurrentProcessExplicitAppUserModelID("Sangitam.Desktop.Music");
            var window = new NativeWindow(); var parameters = new CreateParams(); parameters.Caption = "Sangitam"; window.CreateHandle(parameters);
            var factory = (IMediaInterop)WindowsRuntimeMarshal.GetActivationFactory(typeof(SystemMediaTransportControls));
            var iid = new Guid("99fa3ff4-1742-42a6-902e-087d41f965ec"); controls = factory.GetForWindow(window.Handle, ref iid);
            controls.IsPlayEnabled = controls.IsPauseEnabled = controls.IsStopEnabled = controls.IsNextEnabled = controls.IsPreviousEnabled = true;
            controls.ButtonPressed += (sender, e) => {
                switch (e.Button) {
                    case SystemMediaTransportControlsButton.Play: Send("PLAY"); break;
                    case SystemMediaTransportControlsButton.Pause: Send("PAUSE"); break;
                    case SystemMediaTransportControlsButton.Stop: Send("STOP"); break;
                    case SystemMediaTransportControlsButton.Next: Send("NEXT"); break;
                    case SystemMediaTransportControlsButton.Previous: Send("PREVIOUS"); break;
                }
            };
            controls.PlaybackPositionChangeRequested += (sender, e) => Send("SEEK\t" + e.RequestedPlaybackPosition.TotalSeconds.ToString(CultureInfo.InvariantCulture));
            controls.ShuffleEnabledChangeRequested += (sender, e) => { if (!applying) Send("SHUFFLE\t" + (e.RequestedShuffleEnabled ? "1" : "0")); };
            controls.AutoRepeatModeChangeRequested += (sender, e) => { if (!applying) Send("REPEAT\t" + (e.RequestedAutoRepeatMode == MediaPlaybackAutoRepeatMode.Track ? "1" : "0")); };
            Send("READY");
            var pipe = new Thread(() => {
                try { string line; while ((line = Console.ReadLine()) != null) Apply(line); }
                catch { Send("ERROR"); }
                finally { try { controls.IsEnabled = false; } catch { } Application.Exit(); }
            }); pipe.IsBackground = true; pipe.Start(); Application.Run();
            controls.IsEnabled = false; window.DestroyHandle(); return 0;
        } catch { Send("ERROR"); return 1; }
    }
}
