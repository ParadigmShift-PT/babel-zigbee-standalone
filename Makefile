all: build run

build:
	mvn clean package -P executable

run:
	java -jar target/babel-zigbee-*-executable.jar

