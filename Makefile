MVN := ./mvnw

.PHONY: all compile run test clean

all: compile

compile:
	$(MVN) -q -DskipTests compile

run: compile
	$(MVN) -q exec:java

test:
	$(MVN) -q test

clean:
	$(MVN) -q clean
	rm -rf bin
