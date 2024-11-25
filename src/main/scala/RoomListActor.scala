package fr.cytech.icc

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors

enum RoomListMessage {
  case ListRooms(replyTo: ActorRef[List[String]])
  case CreateRoom(name: String)
  case GetRoom(name: String, replyTo: ActorRef[Option[ActorRef[Message]]])
}

object RoomListActor {

  import RoomListMessage.*

  def apply(
      rooms: Map[String, ActorRef[Message]] = Map.empty
  ): Behavior[RoomListMessage] = {
    Behaviors.setup { context =>
      Behaviors.receiveMessage {
        case CreateRoom(name) => {
          val room_actor = context.spawn(RoomActor(name),"room:"+name)
          RoomListActor(rooms + (name -> room_actor))
        }
        case GetRoom(name, replyTo) =>
          replyTo ! (rooms.get(name)); Behaviors.same
        case ListRooms(replyTo) =>
          replyTo ! (rooms.keys.toList); Behaviors.same
      }
    }
  }
}
