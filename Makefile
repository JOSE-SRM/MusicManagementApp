JAVAC := javac
JAVA := java
SRC_DIR := src
BIN_DIR := bin
MAIN_CLASS := sangitam.desktop.app.SangitamApp
SOURCES := $(shell find $(SRC_DIR) -name "*.java")

.PHONY: all compile run clean

all: compile

compile:
	@mkdir -p $(BIN_DIR)
	$(JAVAC) -encoding UTF-8 -d $(BIN_DIR) $(SOURCES)

run: compile
	$(JAVA) -cp $(BIN_DIR) $(MAIN_CLASS)

clean:
	rm -rf $(BIN_DIR)
