all: build run

build:
	mvn clean package

run:
	java -jar target/babel.zigbee-0.0.1.jar

