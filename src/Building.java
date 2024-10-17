import com.rabbitmq.client.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.UUID;

public class Building {
    protected String exchangeName;
    protected final String queueName; // equals buildingName

    protected Channel channel;

    private static final String buildingName = "Building" + (UUID.randomUUID().toString().substring(0, 4));

    private HashMap<String, String> rooms = new HashMap<>();

    public Building(String exchangeName, String queueName, String exchangeType) {
        this.exchangeName = exchangeName;
        this.queueName = queueName;
        try {
            // for all messages except getName
            Connection connection = new ConnectionFactory().newConnection();
            channel = connection.createChannel();
            channel.exchangeDeclare(exchangeName, exchangeType);
            channel.queueDeclare(queueName, false, false, false, null);
            channel.queueBind(queueName, exchangeName, queueName);
            channel.exchangeDeclare("building_broadcast_exchange", "fanout");
            channel.queueBind(queueName, "building_broadcast_exchange", "");
        } catch (Exception e) {
            e.printStackTrace();
        }
        // Add some rooms
        rooms.put("Room1", "");
        rooms.put("Room2", "");
        rooms.put("Room3", "");
        rooms.put("Room4", "");
    }

    public static void main(String[] args) throws Exception {
        new Building("building_Exchange", buildingName, "direct").run();
    }

    private void run() throws InterruptedException, IOException {
        consumeMessage();

        while (true) {
            // this keeps the agent running
            // otherwise the agent will stop, and the connection will be closed
            Thread.sleep(1000);
        }
    }

    public void consumeMessage() throws IOException {
        channel.basicConsume(queueName, true, (consumerTag, delivery) -> {
            String message = new String(delivery.getBody(), "UTF-8");
            System.out.println("Received message: " + message);
            handleMessage(message, delivery);
        }, consumerTag -> {
        });
    }

    private void handleMessage(String message, Delivery delivery) throws IOException {
        String[] messageParts = message.split(":");
        String action = messageParts[0];
        String responseQueue = messageParts[messageParts.length - 1]; // This is the agent's response queue
        String originalClientReplyTo = delivery.getProperties().getReplyTo(); // The client's reply-to forwarded by the agent

        switch (action) {
            case "REQUEST_BUILDING_NAME":
                // Send response back to the agent's response queue, with original reply-to for client
                sendReturnMessage("BUILDING_NAME_RESPONSE:" + buildingName, responseQueue, originalClientReplyTo);
                break;
            case "REQUEST_ROOMS":
                // Respond with room details to the agent's response queue, with original reply-to for client
                sendReturnMessage("ROOMS_RESPONSE:" + "ID;" + buildingName + ":roomkeys;" + rooms.keySet(), responseQueue, originalClientReplyTo);
                break;
            case "REQUEST_RESERVATION":
                String room = messageParts[1];
                String owner = delivery.getProperties().getReplyTo();
                AMQP.BasicProperties props = new AMQP.BasicProperties.Builder().replyTo(owner).build();
                if (rooms.get(room).equalsIgnoreCase("")) {

                    String uuid = UUID.randomUUID().toString().substring(0, 6);
                    String returnMessage = buildingName + "@" + uuid;
                    rooms.put(room, uuid);
                    sendReturnMessage("RESERVATION_RESPONSE:" + returnMessage, responseQueue, originalClientReplyTo);
                } else {
                    sendReturnMessage("RESERVATION_RESPONSE: Room doesn't exist or is already taken", responseQueue, originalClientReplyTo);
                }
                break;
            case "CONFIRM_RESERVATION":
                String reservationID = messageParts[1];
                if (rooms.containsValue(reservationID)) {
                    sendReturnMessage("CONFIRMATION_RESPONSE: Reservation confirmed", responseQueue, originalClientReplyTo);
                } else {
                    sendReturnMessage("CONFIRMATION_RESPONSE: Reservation not found", responseQueue, originalClientReplyTo);
                }
                break;
            case "CANCEL_RESERVATION":
                String reservationID2 = messageParts[1];
                if (rooms.containsValue(reservationID2)) {
                    rooms.entrySet().stream().filter(entry -> entry.getValue().equals(reservationID2)).forEach(entry -> {
                        rooms.put(entry.getKey(), "");
                    });
                    sendReturnMessage("CANCELLATION_RESPONSE:" + reservationID2, responseQueue, originalClientReplyTo);
                } else {
                    sendReturnMessage("CANCELLATION_RESPONSE: Reservation not found", responseQueue, originalClientReplyTo);
                }
                break;
            default:
                System.out.println("Unknown action: " + action);
        }
    }

    public void sendReturnMessage(String message, String responseQueue, String originalClientReplyTo) {
        try {
            // Set the reply-to for the original client in the AMQP properties
            AMQP.BasicProperties props = new AMQP.BasicProperties.Builder().replyTo(originalClientReplyTo)  // Forward the original client's reply-to queue
                    .build();

            // Send response to agent's response queue, with original reply-to for client
            channel.basicPublish("", responseQueue, props, message.getBytes());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
