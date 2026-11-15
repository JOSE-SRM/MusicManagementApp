package sangitam.desktop.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/** Accesses the current Windows user's Credential Manager without persisting credentials in app files. */
public final class WindowsCredentialStore {
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();
    private static final String NATIVE_BRIDGE = """
        using System;
        using System.Runtime.InteropServices;
        using System.Text;
        public static class SangitamCred {
          [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)]
          public struct CREDENTIAL {
            public UInt32 Flags; public UInt32 Type; public IntPtr TargetName; public IntPtr Comment;
            public long LastWritten; public UInt32 CredentialBlobSize; public IntPtr CredentialBlob;
            public UInt32 Persist; public UInt32 AttributeCount; public IntPtr Attributes;
            public IntPtr TargetAlias; public IntPtr UserName;
          }
          [DllImport("Advapi32.dll", EntryPoint="CredWriteW", CharSet=CharSet.Unicode, SetLastError=true)]
          public static extern bool CredWrite(ref CREDENTIAL credential, UInt32 flags);
          [DllImport("Advapi32.dll", EntryPoint="CredReadW", CharSet=CharSet.Unicode, SetLastError=true)]
          public static extern bool CredRead(string target, UInt32 type, UInt32 flags, out IntPtr credential);
          [DllImport("Advapi32.dll", EntryPoint="CredDeleteW", CharSet=CharSet.Unicode, SetLastError=true)]
          public static extern bool CredDelete(string target, UInt32 type, UInt32 flags);
          [DllImport("Advapi32.dll")] public static extern void CredFree(IntPtr buffer);
          public static bool Write(string target, string value) {
            byte[] bytes = Encoding.UTF8.GetBytes(value);
            IntPtr blob = Marshal.AllocHGlobal(bytes.Length);
            IntPtr targetPtr = Marshal.StringToCoTaskMemUni(target);
            IntPtr userPtr = Marshal.StringToCoTaskMemUni(Environment.UserName);
            try {
              Marshal.Copy(bytes, 0, blob, bytes.Length);
              CREDENTIAL c = new CREDENTIAL(); c.Type=1; c.TargetName=targetPtr;
              c.CredentialBlob=blob; c.CredentialBlobSize=(UInt32)bytes.Length;
              c.Persist=2; c.UserName=userPtr; return CredWrite(ref c, 0);
            } finally { Marshal.FreeHGlobal(blob); Marshal.FreeCoTaskMem(targetPtr); Marshal.FreeCoTaskMem(userPtr); }
          }
          public static string Read(string target) {
            IntPtr pointer; if (!CredRead(target, 1, 0, out pointer)) return "";
            try {
              CREDENTIAL c=(CREDENTIAL)Marshal.PtrToStructure(pointer, typeof(CREDENTIAL));
              byte[] bytes=new byte[c.CredentialBlobSize]; Marshal.Copy(c.CredentialBlob, bytes, 0, bytes.Length);
              return Encoding.UTF8.GetString(bytes);
            } finally { CredFree(pointer); }
          }
        }
        """;

    private WindowsCredentialStore() { }

    public static boolean isAvailable() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    public static String read(String key) {
        if (CACHE.containsKey(key)) return CACHE.get(key);
        if (!isAvailable()) return null;
        String output = invoke("Read", target(key), "");
        String value = output == null || output.isBlank() ? null : output;
        if (value != null) CACHE.put(key, value);
        return value;
    }

    public static void write(String key, String secret) {
        if (!isAvailable()) throw new IllegalStateException("Secure provider settings require Windows Credential Manager.");
        if (secret == null || secret.isBlank()) { delete(key); return; }
        String output = invoke("Write", target(key), secret);
        if (!"OK".equals(output)) throw new IllegalStateException("Windows Credential Manager could not save this setting.");
        CACHE.put(key, secret);
    }

    public static void delete(String key) {
        CACHE.remove(key);
        if (isAvailable()) invoke("Delete", target(key), "");
    }

    private static String target(String key) { return "Sangitam/" + key; }

    private static String invoke(String operation, String target, String input) {
        String script = "$ErrorActionPreference='Stop'; Add-Type -TypeDefinition @'\n" + NATIVE_BRIDGE
            + "\n'@; $target='" + target.replace("'", "''") + "'; switch ('" + operation + "') { "
            + "'Read' { [Console]::Out.Write([SangitamCred]::Read($target)) } "
            + "'Write' { $value=[Console]::In.ReadToEnd(); if ([SangitamCred]::Write($target,$value)) { [Console]::Out.Write('OK') } else { exit 2 } } "
            + "'Delete' { [void][SangitamCred]::CredDelete($target,1,0); [Console]::Out.Write('OK') } }";
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        Process process;
        try {
            process = new ProcessBuilder("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded).start();
            if (operation.equals("Write")) {
                process.getOutputStream().write(input.getBytes(StandardCharsets.UTF_8));
            }
            process.getOutputStream().close();
            if (!process.waitFor(8, TimeUnit.SECONDS)) { process.destroyForcibly(); return null; }
            byte[] output = process.getInputStream().readAllBytes();
            return process.exitValue() == 0 ? new String(output, StandardCharsets.UTF_8).strip() : null;
        } catch (IOException error) {
            return null;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
