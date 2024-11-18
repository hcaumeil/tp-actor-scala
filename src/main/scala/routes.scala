package fr.cytech.icc

import java.time.OffsetDateTime
import java.util.UUID
import scala.concurrent.{ ExecutionContext, Future }

import Message.LatestPost
import RoomListMessage.GetRoom
import org.apache.pekko.actor.typed.{ ActorRef, Scheduler }
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import org.apache.pekko.http.scaladsl.marshalling.ToResponseMarshallable
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.{ Directives, Route }
import org.apache.pekko.util.Timeout
import spray.json.*

case class PostInput(author: String, content: String)

case class RoomInput(name: String)

case class PostOutput(id: UUID, room: String, author: String, content: String, postedAt: OffsetDateTime)

object PostOutput {

  extension (post: Post) {

    def output(roomId: String): PostOutput = PostOutput(
      id = post.id,
      room = roomId,
      author = post.author,
      content = post.content,
      postedAt = post.postedAt
    )
  }
}

case class Controller(
    rooms: ActorRef[RoomListMessage]
  )(using
    ExecutionContext,
    Timeout,
    Scheduler)
    extends Directives,
      SprayJsonSupport,
      DefaultJsonProtocol {

  import PostOutput.output

  given JsonFormat[UUID] = new JsonFormat[UUID] {
    def write(uuid: UUID): JsValue = JsString(uuid.toString)

    def read(value: JsValue): UUID = {
      value match {
        case JsString(uuid) => UUID.fromString(uuid)
        case _              => throw DeserializationException("Expected hexadecimal UUID string")
      }
    }
  }

  given JsonFormat[OffsetDateTime] = new JsonFormat[OffsetDateTime] {
    def write(dateTime: OffsetDateTime): JsValue = JsString(dateTime.toString)

    def read(value: JsValue): OffsetDateTime = {
      value match {
        case JsString(dateTime) => OffsetDateTime.parse(dateTime)
        case _                  => throw DeserializationException("Expected ISO 8601 OffsetDateTime string")
      }
    }
  }

  given RootJsonFormat[PostInput] = {
    jsonFormat2(PostInput.apply)
  }

  given RootJsonFormat[RoomInput] = {
    jsonFormat1(RoomInput.apply)
  }

  given RootJsonFormat[PostOutput] = {
    jsonFormat5(PostOutput.apply)
  }

  val routes: Route = concat(
    path("rooms") {
      post {
        entity(as[RoomInput]) { payload => createRoom(payload) }
      } ~ get {
        listRooms()
      }
    },
    path("rooms" / Segment) { roomId =>
      get {
        getRoom(roomId)
      }
    },
    path("rooms" / Segment / "posts") { roomId =>
      post {
        entity(as[PostInput]) { payload => createPost(roomId, payload) }
      } ~ get {
        listPosts(roomId)
      }
    },
    path("rooms" / Segment / "posts" / "latest") { roomId =>
      get {
        complete(getLatestPost(roomId))
      }
    },
    path("rooms" / Segment / "posts" / Segment) { (roomId, messageId) =>
      get {
        getPost(roomId, messageId)
      }
    }
  )

  private def createRoom(input: RoomInput) = ???

  private def listRooms() = ???

  private def getRoom(roomId: String) = ???

  private def createPost(roomId: String, input: PostInput) = ???

  private def listPosts(roomId: String) = ???

  private def getLatestPost(roomId: String): Future[ToResponseMarshallable] =
    rooms
      .ask[Option[ActorRef[Message]]](ref => GetRoom(roomId, ref))
      .flatMap {
        case Some(roomActorRef) => roomActorRef.ask[Option[Post]](ref => Message.LatestPost(ref))
        case None               => Future.successful(None)
      }
      .map {
        case Some(post) =>
          StatusCodes.OK -> post.output(roomId)
        case None =>
          StatusCodes.NotFound
      }

  private def getPost(roomId: String, messageId: String) = ???
}
