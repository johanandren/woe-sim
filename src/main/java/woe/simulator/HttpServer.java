package woe.simulator;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.cluster.sharding.typed.javadsl.ClusterSharding;
import akka.cluster.sharding.typed.javadsl.EntityRef;
import akka.http.javadsl.ConnectHttp;
import akka.http.javadsl.Http;
import akka.http.javadsl.marshallers.jackson.Jackson;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.server.Route;
import akka.stream.Materializer;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;

import static akka.http.javadsl.server.Directives.*;
import static woe.simulator.WorldMap.entityIdOf;
import static woe.simulator.WorldMap.regionForZoom0;

class HttpServer {
  private final ActorSystem<?> actorSystem;
  private final ClusterSharding clusterSharding;
  private ActorRef<Region.Command> replyTo; // hack for unit testing

  static HttpServer start(String host, int port, ActorSystem<?> actorSystem) {
    return new HttpServer(host, port, actorSystem);
  }

  private HttpServer(String host, int port, ActorSystem<?> actorSystem) {
    this.actorSystem = actorSystem;
    clusterSharding = ClusterSharding.get(actorSystem);

    start(host, port);
  }

  private void start(String host, int port) {
    Materializer materializer = Materializer.matFromSystem(actorSystem);

    Http.get(actorSystem.classicSystem())
        .bindAndHandle(route().flow(actorSystem.classicSystem(), materializer),
            ConnectHttp.toHost(host, port), materializer);

    log().info("HTTP Server started on {}:{}", host, "" + port);
  }

  private Route route() {
    return concat(
        path("selection", this::handleSelectionActionPost)
    );
  }

  private Route handleSelectionActionPost() {
    return post(
        () -> entity(
            Jackson.unmarshaller(SelectionActionRequest.class),
            selectionActionRequest -> {
              try {
                log().debug("POST {}", selectionActionRequest);
                submit(selectionActionRequest);
                return complete(StatusCodes.OK, SelectionActionResponse.ok(StatusCodes.OK.intValue(), selectionActionRequest), Jackson.marshaller());
              } catch (IllegalArgumentException e) {
                log().warn("POST failed {}", selectionActionRequest);
                return complete(StatusCodes.BAD_REQUEST, SelectionActionResponse.failed(e.getMessage(), StatusCodes.BAD_REQUEST.intValue(), selectionActionRequest), Jackson.marshaller());
              }
            }
        )
    );
  }

  private void submit(SelectionActionRequest selectionActionRequest) {
    Region.SelectionCommand selectionCommand = selectionActionRequest.asSelectionAction(replyTo);
    String entityId = entityIdOf(regionForZoom0());
    EntityRef<Region.Command> entityRef = clusterSharding.entityRefFor(Region.entityTypeKey, entityId);
    entityRef.tell(selectionCommand);
  }

  public static class SelectionActionRequest {
    public final String action;
    public final int zoom;
    public final double topLeftLat;
    public final double topLeftLng;
    public final double botRightLat;
    public final double botRightLng;

    @JsonCreator
    public SelectionActionRequest(
        @JsonProperty("action") String action,
        @JsonProperty("zoom") int zoom,
        @JsonProperty("topLeftLat") double topLeftLat,
        @JsonProperty("topLeftLng") double topLeftLng,
        @JsonProperty("botRightLat") double botRightLat,
        @JsonProperty("botRightLng") double botRightLng) {
      this.action = action;
      this.zoom = zoom;
      this.topLeftLat = topLeftLat;
      this.topLeftLng = topLeftLng;
      this.botRightLat = botRightLat;
      this.botRightLng = botRightLng;
    }

    SelectionActionRequest(String action, WorldMap.Region region) {
      this(action, region.zoom, region.topLeft.lat, region.topLeft.lng, region.botRight.lat, region.botRight.lng);
    }

    Region.SelectionCommand asSelectionAction(ActorRef<Region.Command> replyTo) {
      WorldMap.Region region = new WorldMap.Region(zoom, WorldMap.topLeft(topLeftLat, topLeftLng), WorldMap.botRight(botRightLat, botRightLng));
      switch (action) {
        case "create":
          return new Region.SelectionCreate(region, replyTo);
        case "delete":
          return new Region.SelectionDelete(region, replyTo);
        case "happy":
          return new Region.SelectionHappy(region, replyTo);
        case "sad":
          return new Region.SelectionSad(region, replyTo);
        default:
          throw new IllegalArgumentException(String.format("Action '%s' illegal, must be one of: 'create', 'delete', 'happy', or 'sad'.", action));
      }
    }

    @Override
    public String toString() {
      return String.format("%s[%s, %d, %1.9f, %1.9f, %1.9f, %1.9f]", getClass().getSimpleName(), action, zoom, topLeftLat, topLeftLng, botRightLat, botRightLng);
    }
  }

  public static class SelectionActionResponse {
    public final String message;
    public final int httpStatusCode;
    public final SelectionActionRequest selectionActionRequest;

    @JsonCreator
    public SelectionActionResponse(
        @JsonProperty("message") String message,
        @JsonProperty("httpStatusCode") int httpStatusCode,
        @JsonProperty("selectionActionRequest") SelectionActionRequest selectionActionRequest) {
      this.message = message;
      this.httpStatusCode = httpStatusCode;
      this.selectionActionRequest = selectionActionRequest;
    }

    static SelectionActionResponse ok(int httpStatusCode, SelectionActionRequest selectionActionRequest) {
      return new SelectionActionResponse("Accepted", httpStatusCode, selectionActionRequest);
    }

    static SelectionActionResponse failed(String message, int httpStatusCode, SelectionActionRequest selectionActionRequest) {
      return new SelectionActionResponse(message, httpStatusCode, selectionActionRequest);
    }

    @Override
    public String toString() {
      return String.format("%s[%d, %s, %s]", getClass().getSimpleName(), httpStatusCode, message, selectionActionRequest);
    }
  }

  // Hack for unit testing
  void replyTo(ActorRef<Region.Command> replyTo) {
    this.replyTo = replyTo;
  }

  private Logger log() {
    return actorSystem.log();
  }
}
