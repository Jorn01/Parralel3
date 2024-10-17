import com.rabbitmq.client.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Agent {
    protected String exchangeName = "client_request_exchange";
    protected final String queueName = "request";

    protected Channel channel;
    private final String responseName = "Agent_Response" + UUID.randomUUID();
    private final List<String> buildings = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Agent agent = new Agent();
        agent.run();
    }

    private void run() throws InterruptedException, IOException {
        try {
            Connection connection = new ConnectionFactory().newConnection();
            channel = connection.createChannel();
            channel.exchangeDeclare(exchangeName, "direct");
            channel.queueDeclare(queueName, false, false, false, null);
            channel.queueBind(queueName, exchangeName, "request");
            channel.exchangeDeclare("building_Exchange", "direct");
            channel.queueDeclare(responseName, false, false, false, null);
            channel.queueBind(responseName, exchangeName, "");
        } catch (Exception e) {
            e.printStackTrace();
        }
        consumeMessage();
        while (true) {
            Thread.sleep(1000);
        }
    }

    public void consumeMessage() throws IOException {
        channel.basicConsume(queueName, true, (consumerTag, delivery) -> {
            String message = new String(delivery.getBody(), "UTF-8");
            System.out.println("Received message: " + message);
            // Extract reply-to from message properties
            String replyTo = delivery.getProperties().getReplyTo();
            handleMessage(message, replyTo);
        }, consumerTag -> {
        });

        channel.basicConsume(responseName, true, getDeliverCallback(), consumerTag -> {
        });
    }

    private void handleMessage(String message, String replyTo) throws IOException {
        String[] messageParts = message.split(":");
        String action = messageParts[0];

        // If no reply-to, ignore the message
        if (replyTo == null) {
            System.out.println("No reply-to queue found, ignoring message.");
            return;
        }

        switch (action) {
            case "GET_LIST_OF_BUILDINGS":
                getListOfBuildings(replyTo); // Pass the reply-to queue from the client
                break;
            case "GET_LIST_OF_ROOMS":
                System.out.println(messageParts[1]);
                getListOfRooms(messageParts[1], replyTo); // Pass the reply-to queue from the client
                break;
            case "RESERVE_ROOM":
                reserveRoom(messageParts[1], messageParts[2], replyTo); // Pass the reply-to queue from the client
                break;
            case "CONFIRM_RESERVATION":
                String[] parts = messageParts[1].split("@", 2);
                AMQP.BasicProperties props = new AMQP.BasicProperties.Builder().replyTo(replyTo).build();
                channel.basicPublish("building_Exchange", parts[0], props, ("CONFIRM_RESERVATION:" + parts[1] + ":" + responseName).getBytes());
                break;
            case "CANCEL_RESERVATION":
                String[] parts2 = messageParts[1].split("@", 2);
                AMQP.BasicProperties props2 = new AMQP.BasicProperties.Builder().replyTo(replyTo).build();
                channel.basicPublish("building_Exchange", parts2[0], props2, ("CANCEL_RESERVATION:" + parts2[1] + ":" + responseName).getBytes());
                break;
            default:
                System.out.println("Unknown action: " + action);
        }
    }

    private void reserveRoom(String buildingID, String roomID, String replyTo) {
        try {
            AMQP.BasicProperties props = new AMQP.BasicProperties.Builder().replyTo(replyTo).build();
            channel.basicPublish("building_Exchange", buildingID, props, ("REQUEST_RESERVATION:" + roomID + ":" + responseName).getBytes());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void getListOfRooms(String buildingID, String clientReplyTo) {
        try {
            AMQP.BasicProperties props = new AMQP.BasicProperties.Builder().replyTo(clientReplyTo).build();
            channel.basicPublish("building_Exchange", buildingID, props, ("REQUEST_ROOMS:" + responseName).getBytes());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private DeliverCallback getDeliverCallback() {
        return (consumerTag, delivery) -> {
            String body = new String(delivery.getBody(), "UTF-8");
            String[] messageParts = body.split(":");
            String action = messageParts[0];
            String replyTo = delivery.getProperties().getReplyTo();

            switch (action) {
                case "BUILDING_NAME_RESPONSE":
                    if (!buildings.contains(messageParts[1])) {
                        buildings.add(messageParts[1]);  // Add building to list
                        System.out.println("Received building: " + messageParts[1]);
                    }
                    break;
                case "ROOMS_RESPONSE":
                    String buildingID = messageParts[1].split(";")[1];
                    String[] roomKeys = messageParts[2].split(";");
                    channel.basicPublish("", replyTo, null, ("ROOMS_RESPONSE:" + buildingID + ":" + Arrays.toString(roomKeys)).getBytes());
                    break;
                case "RESERVATION_RESPONSE":
                    String reservationID = messageParts[1];
                    channel.basicPublish("", replyTo, null, ("RESERVATION_RESPONSE:" + reservationID).getBytes());
                    break;
                case "CONFIRMATION_RESPONSE":
                    String confirmation = messageParts[1];
                    channel.basicPublish("", replyTo, null, ("CONFIRMATION_RESPONSE:" + confirmation).getBytes());
                    break;
                case "CANCELLATION_RESPONSE":
                    String cancellation = messageParts[1];
                    channel.basicPublish("", replyTo, null, ("CANCELLATION_RESPONSE:" + cancellation).getBytes());
                    break;
                default:
                    System.out.println("Unknown action: " + action);
            }
        };
    }


    private void getListOfBuildings(String clientReplyTo) {
        try {
            // Forward request to building with agent's response queue appended
            String queueName = responseName + "building_request_queue";
            channel.queueDeclare(queueName, false, false, false, null);
            channel.queueBind(queueName, exchangeName, "");
            channel.basicPublish("building_broadcast_exchange", "", null, ("REQUEST_BUILDING_NAME:" + queueName).getBytes());
            String consumerTag = channel.basicConsume(queueName, true, getDeliverCallback(), consumerTag1 -> {
            });

            // Cancel the consumer after 5 seconds if no response
            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
            scheduler.schedule(() -> {
                try {
                    channel.basicCancel(consumerTag);
                    System.out.println("Cancelled consumer after 5 seconds");
                    StringBuilder buildings = new StringBuilder();
                    for (String building : this.buildings) {
                        buildings.append(building).append(",");
                    }
                    channel.basicPublish("", clientReplyTo, null, ("BUILDING_NAME_RESPONSE:" + buildings).getBytes());
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }, 5, TimeUnit.SECONDS);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
