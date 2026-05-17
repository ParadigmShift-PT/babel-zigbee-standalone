all: build run

build:
	mvn clean package -P executable

run:
	java -jar target/babel-zigbee-0.0.1-executable.jar

