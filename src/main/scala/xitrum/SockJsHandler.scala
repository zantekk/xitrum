package xitrum

import akka.actor.ActorRef
import com.codahale.jerkson.Json
import org.jboss.netty.channel.ChannelFutureListener

import xitrum.routing.Routes
import xitrum.sockjs.SockJsNonWebSocketSessions

abstract class SockJsHandler extends Logger {
  /** Set by SockJsController; true if raw WebSocket transport is used */
  var rawWebSocket = false

  /** Set by SockJsController; null if WebSocket (raw or not) is used (polling is not used) */
  var sockJsNonWebSocketSessionActorRef: ActorRef = null

  /** Set by SockJsController; null if WebSocket (raw or not) is not used (polling is used) */
  var webSocketController: Controller = null

  //----------------------------------------------------------------------------
  // Abstract methods that must be implemented by apps

  def onOpen()
  def onMessage(message: String)
  def onClose()

  //----------------------------------------------------------------------------
  // Helper methods for apps to use

  def send(message: Any) {
    if (webSocketController == null) {
      // FIXME: Ugly code
      // sockJsNonWebSocketSessionActorRef is set to null by SockJsNonWebSocketSession on postStop
      if (sockJsNonWebSocketSessionActorRef != null) {
        if (!SockJsNonWebSocketSessions.sendMessageByHandler(sockJsNonWebSocketSessionActorRef, message.toString))
          onClose()
      }
    } else {
      // WebSocket is used, but it may be raw or not raw
      if (rawWebSocket) {
        webSocketController.respondWebSocket(message)
      } else {
        webSocketController.respondWebSocket("a" + Json.generate(List(message)))
      }
    }
  }

  def close() {
    if (webSocketController == null) {
      // Until the timeout occurs, the server must serve the close message
      SockJsNonWebSocketSessions.closeByHandler(sockJsNonWebSocketSessionActorRef)
    } else {
      // For WebSocket, must explicitly close
      // WebSocket is used, but it may be raw or not raw
      if (rawWebSocket) {
        webSocketController.channel.close()
      } else {
        webSocketController.respondWebSocket("c[3000,\"Go away!\"]")
        .addListener(ChannelFutureListener.CLOSE)
      }
    }
  }

  def url: String = {
    Config.withBaseUrl(Routes.sockJsPathPrefix(this.getClass))
  }
}
