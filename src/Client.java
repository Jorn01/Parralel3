import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.AMQP;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.util.concurrent.TimeoutException;

public class Client {

    private final static String CLIENT_REQUEST_EXCHANGE = "client_request_exchange";
    private final static String CLIENT_RESPONSE_QUEUE = "client_response_queue_";
    private static String responseQueueName;
    private Channel channel;

    public static void main(String[] args) throws Exception {
        new Client().run();
    }

    private void run() {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");
        try {
            Connection connection = factory.newConnection();
            channel = connection.createChannel();
            // Declare request exchange and response queue
            channel.exchangeDeclare(CLIENT_REQUEST_EXCHANGE, "direct");
            responseQueueName = CLIENT_RESPONSE_QUEUE + java.util.UUID.randomUUID();
            channel.queueDeclare(responseQueueName, false, true, true, null);

            // Listen for responses from the agents
            channel.basicConsume(responseQueueName, true, (consumerTag, delivery) -> {
                String message = new String(delivery.getBody(), "UTF-8");
                handleResponse(message);
            }, consumerTag -> {
            });

            Scanner scanner = new Scanner(System.in);
            while (true) {
                System.out.println("Select an action:");
                System.out.println("1: Request list of buildings");
                System.out.println("2: Request list of rooms in a building");
                System.out.println("3: Reserve a meeting room");
                System.out.println("4: Confirm reservation");
                System.out.println("5: Cancel reservation");
                System.out.println("0: Exit");

                int choice = scanner.nextInt();
                scanner.nextLine(); // Consume newline

                if (choice == 0) {
                    break;
                }

                String message = "";
                String buildingID;
                switch (choice) {
                    case 1:
                        message = "GET_LIST_OF_BUILDINGS";
                        break;
                    case 2:
                        System.out.println("Enter building ID:");
                        buildingID = scanner.nextLine();
                        message = "GET_LIST_OF_ROOMS:" + buildingID;
                        break;
                    case 3:
                        System.out.println("Enter building ID:");
                        buildingID = scanner.nextLine();
                        System.out.println("Enter room ID:");
                        String roomId = scanner.nextLine();
                        message = "RESERVE_ROOM:" + buildingID + ":" + roomId;
                        break;
                    case 4:
                        System.out.println("Enter reservation number to confirm:");
                        String reservationNumber = scanner.nextLine();
                        message = "CONFIRM_RESERVATION:" + reservationNumber;
                        break;
                    case 5:
                        System.out.println("Enter reservation number to cancel:");
                        String cancelReservationNumber = scanner.nextLine();
                        message = "CANCEL_RESERVATION:" + cancelReservationNumber;
                        break;
                    default:
                        System.out.println("Invalid choice. Try again.");
                        continue;
                }

                sendMessage(message);
            }
        } catch (IOException | TimeoutException e) {
            e.printStackTrace();
        } finally {
            try {
                channel.close();
            } catch (IOException | TimeoutException e) {
                e.printStackTrace();
            }
        }
    }

    private void handleResponse(String message) {
        String[] messageParts = message.split(":");
        String action = messageParts[0];
        switch (action) {
            case "BUILDING_NAME_RESPONSE":
                StringBuilder buildingList = new StringBuilder("Buildings: ");
                String buildings = messageParts[1].replace("[","").replace("]","");
                if(buildings.isEmpty()) {
                    System.out.println("No buildings found");
                    return;
                }
                String[] buildingNames = buildings.split(",");
                if(buildingNames.length == 0) {
                    System.out.println(buildings);
                    return;
                }
                for (String buildingName : buildingNames) {
                    buildingList.append(buildingName).append(System.lineSeparator());
                }
                System.out.println(buildingList);
                break;
            case "ROOMS_RESPONSE":
                StringBuilder roomList = new StringBuilder(messageParts[1] + " rooms: ");
                roomList.append(System.lineSeparator());
                String[] roomNames = messageParts[2].replace("[","").replace("]","").split(",");
                for (int i = 1; i < roomNames.length; i++) {
                    roomList.append(roomNames[i]).append(System.lineSeparator());
                }
                System.out.println(roomList);
                break;
            case "RESERVATION_RESPONSE":
                System.out.println("Reservation number: " + messageParts[1]);
                break;
            case "CONFIRMATION_RESPONSE":
                if(messageParts[1].equalsIgnoreCase(" Reservation confirmed")) {
                    System.out.println("Reservation confirmed");
                } else {
                    System.err.println("Reservation not found");
                }
                break;
            case "CANCELLATION_RESPONSE":
                if(messageParts[1].equalsIgnoreCase(" Reservation not found")) {
                    System.err.println("Reservation not found");
                } else {
                    System.out.println("Reservation cancelled: " + messageParts[1]);
                }
                break;
            default:
                System.out.println("Unknown response: " + message);
        }
    }

    private void sendMessage(String message) throws IOException {
        // Set reply-to in the message properties instead of the message body
        AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                .replyTo(responseQueueName) // Set reply-to in properties
                .build();

        // Publish message with properties
        channel.basicPublish(CLIENT_REQUEST_EXCHANGE, "request", props, message.getBytes(StandardCharsets.UTF_8));
        System.out.println("Sent message: " + message);
    }
}
