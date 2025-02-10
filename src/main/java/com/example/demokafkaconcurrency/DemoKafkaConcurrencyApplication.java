package com.example.demokafkaconcurrency;

import com.google.gson.Gson;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

@Configuration
class KafkaConfig {
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }
}

@SpringBootApplication
public class DemoKafkaConcurrencyApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoKafkaConcurrencyApplication.class, args);
    }

}

class AnyDTO {
    private final String testName;
    private long threadId;
    private final Integer responseMillis;
    private final String timestamp;

    public AnyDTO(String testName,
                  Integer responseMillis) {
        this.testName = testName;
        this.responseMillis = responseMillis;
        this.timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
    }

    public String getTestName() {
        return testName;
    }

    public Integer getResponseMillis() {
        return responseMillis;
    }

    public long getThreadId() {
        return threadId;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setThreadId(Long threadId) {
        this.threadId = threadId;
    }

}

@Component
class RequestProcessor {
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final KafkaAsserter kafkaAsserter;

    public RequestProcessor(KafkaAsserter kafkaAsserter, KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaAsserter = kafkaAsserter;
        this.kafkaTemplate = kafkaTemplate;
    }

    public void sendMessage(AnyDTO request) {
        new Thread(() -> {
            try {
                synchronized (this) {
                    // injectinf your current threadId on the request
                    request.setThreadId(Thread.currentThread().getId());
                    Gson gson = new Gson();
                    String jsonMessage = gson.toJson(request);
                    kafkaTemplate.send("request_topic16", jsonMessage);
                }
                AnyDTO response = kafkaAsserter.checkForResponse(request, 100000); // Wait for response
                if (response != null) {
                    System.out.println("---->>>> Response received: " + response.getTestName());
                } else {
                    System.out.println("---->>>> Response timeout: " + request.getTestName());
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start(); // Start the new thread immediately
    }

}

@Service
class KafkaService implements CommandLineRunner {


    private final RequestProcessor requestProcessor;

    public KafkaService(RequestProcessor requestProcessor) {
        this.requestProcessor = requestProcessor;
    }

    @Override
    public void run(String... args) throws Exception {

        Random random = new Random();

        // Test with 20 simultaneous messages
        for (int i = 1; i <= 20; i++) {
            requestProcessor.sendMessage(new AnyDTO("Test" + i, random.nextInt(5000)));
            Thread.sleep(100);
        }
    }


}

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

@Service
class KafkaConsumerService {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Instant applicationStartTime;
    private final KafkaAsserter kafkaAsserter;

    @Value("${kafka.consumer.group-id-request}")
    private String groupIdRequest; // Inject from properties

    @Value("${kafka.consumer.group-id-response}")
    private String groupIdResponse; // Inject from properties

    public KafkaConsumerService(KafkaTemplate<String, String> kafkaTemplate, KafkaAsserter kafkaAsserter) {
        this.kafkaTemplate = kafkaTemplate;
        this.applicationStartTime = Instant.now();
        this.kafkaAsserter = kafkaAsserter;
    }

    @KafkaListener(topics = "request_topic16", groupId = "${kafka.consumer.group-id-request}")
    public void processRequest(String jsonMessage) throws Exception {
        synchronized (this) {
            Gson gson = new Gson();
            AnyDTO request = gson.fromJson(jsonMessage, AnyDTO.class);
            if (request.getResponseMillis() != null) {
                Thread.sleep(request.getResponseMillis());
                kafkaTemplate.send("response_topic16", jsonMessage);
            }
        }

    }

    @KafkaListener(topics = "response_topic16", groupId = "${kafka.consumer.group-id-response}")
    public void processResponse(String jsonMessage) throws Exception {
        synchronized (this) {
            Gson gson = new Gson();
            AnyDTO request = gson.fromJson(jsonMessage, AnyDTO.class);
            if (Instant.parse(request.getTimestamp()).isAfter(applicationStartTime)) {
                kafkaAsserter.AddResponseToQueue(request);
            }
        }
    }
}



