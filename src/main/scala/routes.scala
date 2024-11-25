package fr.cytech.icc

import java.time.OffsetDateTime
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

import Message.LatestPost
import RoomListMessage.*
import scala.collection.immutable.SortedSet
import org.apache.pekko.actor.typed.{ActorRef, Scheduler}
import org.apache.pekko.actor.typed.scaladsl.AskPattern.Askable;
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import org.apache.pekko.http.scaladsl.marshalling.ToResponseMarshallable
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.{Directives, Route}
import org.apache.pekko.util.Timeout
import spray.json.*;
import org.apache.pekko.http.javadsl.model.StatusCode

case class PostInput(author: String, content: String)

case class RoomInput(name: String)

case class PostOutput(
    id: UUID,
    room: String,
    author: String,
    content: String,
    postedAt: OffsetDateTime
)

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
)(using ExecutionContext, Timeout, Scheduler)
    extends Directives,
      SprayJsonSupport,
      DefaultJsonProtocol {

  import PostOutput.output

  given JsonFormat[UUID] = new JsonFormat[UUID] {
    def write(uuid: UUID): JsValue = JsString(uuid.toString)

    def read(value: JsValue): UUID = {
      value match {
        case JsString(uuid) => UUID.fromString(uuid)
        case _ =>
          throw DeserializationException("Expected hexadecimal UUID string")
      }
    }
  }

  given JsonFormat[OffsetDateTime] = new JsonFormat[OffsetDateTime] {
    def write(dateTime: OffsetDateTime): JsValue = JsString(dateTime.toString)

    def read(value: JsValue): OffsetDateTime = {
      value match {
        case JsString(dateTime) => OffsetDateTime.parse(dateTime)
        case _ =>
          throw DeserializationException(
            "Expected ISO 8601 OffsetDateTime string"
          )
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

  // Nasty way to fix private implicit comflict shit...
  given help[A](using e: RootJsonFormat[A]): RootJsonFormat[List[A]] with {
    def read(json: JsValue): List[A] = {
      json match {
        case JsArray(elements) => elements.toList.map(e.read)
        case _                 => List.empty
      }
    }

    def write(obj: List[A]): JsValue = JsArray(obj.map(e.write))
  }

  // Nasty way to fix private implicit comflict shit...
  given RootJsonFormat[String] with {
    def read(json: spray.json.JsValue): String =
      json match {
        case JsString(value) => value
        case _               => ""
      }

    def write(obj: String): spray.json.JsValue =
      JsString(obj)
  }

  val routes: Route = concat(
    path("rooms") {
      post {
        entity(as[RoomInput]) { payload => complete(createRoom(payload)) }
      } ~ get {
        complete(listRooms())
      }
    },
    path("rooms" / Segment / "posts") { roomId =>
      post {
        entity(as[PostInput]) { payload => complete(createPost(roomId, payload)) }
      } ~ get {
        complete(listPosts(roomId))
      }
    },
    path("rooms" / Segment / "posts" / "latest") { roomId =>
      get {
        complete(getLatestPost(roomId))
      }
    },
    path("rooms" / Segment / "posts" / Segment) { (roomId, messageId) =>
      get {
        complete(getPost(roomId, messageId))
      }
    }
  )

  private def createRoom(input: RoomInput) =
    rooms.ask(ref => CreateRoom(input.name)).map(_ => StatusCodes.Created)

  private def listRooms(): Future[ToResponseMarshallable] =
    rooms.ask[List[String]](ref => ListRooms(ref)).map(StatusCodes.OK -> _)

  private def createPost(roomId: String, input: PostInput) : Future[ToResponseMarshallable] =
     rooms
      .ask[Option[ActorRef[Message]]](ref => GetRoom(roomId, ref))
      .flatMap {
        case Some(roomActorRef) =>
          roomActorRef.ask[Unit](ref => Message.CreatePost(input.author, input.content)).map(Some.apply)
        case None => Future.successful(None)
      }
      .map {
        case Some(dd) =>
          StatusCodes.Created
        case None =>
          StatusCodes.BadRequest
      }

  private def listPosts(roomId: String): Future[ToResponseMarshallable] =
    rooms
      .ask[Option[ActorRef[Message]]](ref => GetRoom(roomId, ref))
      .flatMap {
        case Some(actorRef) =>
          actorRef
            .ask[SortedSet[Post]](ref => Message.ListPosts(ref))
            .map(Some.apply)
        case None => Future.successful(None)
      }
      .map {
        case Some(res) =>
          StatusCodes.OK -> res.toList.map(_.output(roomId))
        case None =>
          StatusCodes.NotFound
      }

  private def getLatestPost(roomId: String): Future[ToResponseMarshallable] =
    rooms
      .ask[Option[ActorRef[Message]]](ref => GetRoom(roomId, ref))
      .flatMap {
        case Some(roomActorRef) =>
          roomActorRef.ask[Option[Post]](ref => Message.LatestPost(ref))
        case None => Future.successful(None)
      }
      .map {
        case Some(post) =>
          StatusCodes.OK -> post.output(roomId)
        case None =>
          StatusCodes.NotFound
      }

  private def getPost(
      roomId: String,
      messageId: String
  ): Future[ToResponseMarshallable] =
    rooms
      .ask[Option[ActorRef[Message]]](ref => GetRoom(roomId, ref))
      .flatMap {
        case Some(roomActorRef) =>
          roomActorRef.ask[Option[Post]](ref =>
            Message.GetPost(UUID.fromString(roomId), ref)
          )
        case None => Future.successful(None)
      }
      .map {
        case Some(post) =>
          StatusCodes.OK -> post.output(roomId)
        case None =>
          StatusCodes.NotFound
      }
}
