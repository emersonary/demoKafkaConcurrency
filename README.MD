# Kafka Concurrency Test Project

## Overview

This project is designed to handle multiple responses from Kafka topics and process them concurrently while ensuring
that each response is correctly matched to its originating request. The key mechanism that makes this possible is the *
*KafkaAsserter** class, which tracks responses and ensures they are matched to the correct request based on the thread
ID.

## Motivation

The primary motivation behind this project is to address issues encountered in CI/CD pipelines. The tests executed in
our pipelines run asynchronously, and sometimes, responses from previous tests interfere with the expected results of
new tests. This project aims to create a structured approach to handling these responses, preventing incorrect test
results from affecting the pipeline.

## Assumptions

- Each response is handled by the same thread that generated the request.

## Key Features

- **Concurrency Handling:** The project allows 20 tests to be executed simultaneously.
- **Kafka-Based Communication:** Messages are sent to a Kafka topic and responses are received from another topic.
- **Thread ID Matching:** The `KafkaAsserter` class ensures that responses are mapped to their originating requests
  using thread IDs.
- **Asynchronous Execution:** Each request is processed in a separate thread, allowing high-performance message
  processing.
- **Timeout Handling:** If a response is not received within a specified time, the test logs a timeout message.

## Core Components

### `KafkaAsserter`

The most critical class in the project, `KafkaAsserter`, ensures that the responses are correctly matched to their
respective requests. It:

- Maintains a thread-safe queue (`CopyOnWriteArrayList`) to store responses.
- Provides a mechanism to add responses to the queue.
- Implements a polling mechanism to retrieve responses based on the thread ID, with a timeout mechanism to prevent
  indefinite waiting.

```java

@Component
class KafkaAsserter {

    // List to receive all kafka responses
    private final CopyOnWriteArrayList<AnyDTO> queue = new CopyOnWriteArrayList<>();

    // Method to add Kafka responses into the list
    public void AddResponseToQueue(AnyDTO request) {
        queue.add(request);
    }

    // Method to retrieve the response of your ThreadId (or time out)
    public AnyDTO checkForResponse(AnyDTO request, long timeout) throws InterruptedException {
        long startTime = System.currentTimeMillis();
        int i = 0;
        while (System.currentTimeMillis() - startTime < timeout) {
            if (i < queue.size()) {
                AnyDTO response = queue.get(i);
                if (response.getThreadId() == request.getThreadId()) {
                    response.setThreadId(0L); // It clears the path in case the same thread performs more than one assertion
                    return response;
                }
                i++;
            } else {
                // Ideally this waiting should be handled by a more appropriate mechanism.
                Thread.sleep(100);
            }
        }
        return null; // Timeout reached
    }
}
```

### `RequestProcessor`

- Responsible for sending requests to Kafka.
- Wraps request handling in separate threads.
- Uses `KafkaAsserter` to validate and retrieve responses.

### `KafkaService`

- Acts as the test executor.
- Sends 20 test messages asynchronously to Kafka.

### `KafkaConsumerService`

- Listens to Kafka topics for both requests and responses.

## How It Works

1. `KafkaService` starts execution and generates 20 test messages.
2. Using a different thread for each message, `RequestProcessor.sendMessage` injects the thread id into the DTO object,
   and sends it to the `request_topic16`
   Kafka topic at an interval of 100ms.
3. Meanwhile, this same method waits fot the response by calling `KafkaAsserter.checkForResponse`.
3. The `KafkaConsumerService` receives these requests, processes them, and sends responses to `response_topic16`.
4. The `KafkaConsumerService` captures responses and passes them to `KafkaAsserter`.
5. `KafkaAsserter.checkForResponse` matches responses to their originating requests by checking their thread IDs. If a
   response is received within the timeout period, it returns the DTO; otherwise, it returns null (indicating a
   timeout).

## Running the Project

To run the project, ensure you have:

- Apache Kafka running.
- The necessary Kafka topics (`request_topic16` and `response_topic16`) created.
- The correct `application.properties` configuration for Kafka.

Then, start the application with:

```sh
mvn spring-boot:run
```

## Future Improvements

- Improve response matching efficiency.
- Implement a more sophisticated waiting mechanism to avoid polling inefficiencies.
- Extend test cases for different concurrency scenarios.