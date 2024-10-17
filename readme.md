# What can the program do?
 - The program routes all requests through the agent.
 - On the fly new buildings, agents, or clients can be hooked up.
 - The building generates an uuid for a reservation.
 - The client can request all building names.
 - The client can request rooms from a specific building.

# How does it work?
- ``REQUEST_BUILDING_NAME`` is a request to get all building names.
This works as following. The client sends a request to the agent. The agent forwards the request to the building. The building responds with a list of all building names. The agent then sends a message to the fanout exchange called ``building_broadcast_exchange``. The agent then listens for 5 seconds to a response queue that is bound to the default exchange.
- ``GET_LIST_OF_ROOMS`` is a request to get all rooms from a specific building. The agent forwards the request to the building. This returns the rooms to the agent. The agent then sends it back to the client
- ``RESERVE_ROOM`` is a request to reserve a room. a correct buildingID has to be present otherwise the request ends up on the exchange and no queue picks it up. The agent forwards the request to the building. The building generates a uuid and sends it back to the agent. The agent then sends it back to the client.
- ``CONFIRM_RESERVATION`` is a request to confirm a reservation. The agent forwards the request to the building. The building checks if the uuid is present in the list of reservations. If it is present the building sends a message back to the agent. The agent then sends it back to the client.
- ``CANCEL_RESERVATION`` is a request to cancel a reservation. The agent forwards the request to the building. The building checks if the uuid is present in the list of reservations. If it is present the building removes the uuid from the list of reservations. The building sends a message back to the agent. The agent then sends it back to the client.

## The flow of the program is as follows : 
- The client sends a request to the agent. This request has in the ``reply-to`` field a queue name. This is to track where the response has to go.
- The agent receives the request and processes it. It adds its own queueName to the end of the message after an : and also re-adds the ``reply-to`` field. This is still the original Client queue name.
- The agent sends the message to the building. The building processes the message and sends a response back to the agent.
- The agent receives the response and sends it back to the queue that is in the ``reply-to`` field. This is the client queue name.

``Client`` -> ``Agent`` -> ``Building`` -> ``Agent`` -> ``Client``
So from the agent to the building the response queue is in the message field.
And from the agent to the client the response queue is in the ``reply-to`` field.


Solving the queue problem like this makes all processes more or less stateless. Meaning they can be substituted on the fly.
