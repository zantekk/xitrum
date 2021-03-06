package xitrum.view

import java.io.File
import scala.xml.{Node, NodeSeq, Xhtml}

import org.jboss.netty.buffer.{ChannelBuffer, ChannelBuffers}
import org.jboss.netty.channel.ChannelFuture
import org.jboss.netty.handler.codec.http.{DefaultHttpChunk, HttpChunk, HttpHeaders, HttpVersion}
import org.jboss.netty.handler.codec.http.websocketx.TextWebSocketFrame
import org.jboss.netty.util.CharsetUtil
import HttpHeaders.Names.{CONTENT_TYPE, CONTENT_LENGTH, TRANSFER_ENCODING}
import HttpHeaders.Values.{CHUNKED, NO_CACHE}

import com.codahale.jerkson.Json

import xitrum.{Controller, Config}
import xitrum.controller.Action
import xitrum.etag.NotModified
import xitrum.handler.up.NoPipelining
import xitrum.handler.down.{XSendFile, XSendResource}
import xitrum.routing.Routes

/**
 * When responding text, charset is automatically set, as advised by Google:
 * http://code.google.com/speed/page-speed/docs/rendering.html#SpecifyCharsetEarly
 */
trait Responder extends JS with Flash with Knockout {
  this: Controller =>

  //----------------------------------------------------------------------------

  private var responded = false

  def isResponded = responded

  def respond(): ChannelFuture = {
    if (responded) {
      printDoubleResponseErrorStackTrace()
    } else {
      NoPipelining.setResponseHeaderForKeepAliveRequest(request, response)
      setCookieAndSessionIfTouchedOnRespond()
      val future = channel.write(handlerEnv)
      responded = true

      // Do not handle keep alive:
      // * If XSendFile or XSendResource is used because it is handled by them
      //   in their own way
      // * If the response is chunked because it will be handled by respondLastChunk
      if (!XSendFile.isHeaderSet(response) &&
          !XSendResource.isHeaderSet(response) &&
          !response.isChunked) {
        NoPipelining.if_keepAliveRequest_then_resumeReading_else_closeOnComplete(request, channel, future)
      }

      future
    }
  }

  //----------------------------------------------------------------------------

  /** If Content-Type header is not set, it is set to "application/octet-stream" */
  private def writeHeaderIfFirstChunk() {
    if (!responded) {
      if (!response.containsHeader(CONTENT_TYPE))
        response.setHeader(CONTENT_TYPE, "application/octet-stream")

      // There should be no CONTENT_LENGTH header
      response.removeHeader(CONTENT_LENGTH)

      setNoClientCache()

      // TRANSFER_ENCODING header is automatically set by Netty when it send the
      // real response. We don't need to manually set it here.
      // However, this header is not allowed in HTTP/1.0:
      // http://sockjs.github.com/sockjs-protocol/sockjs-protocol-0.3.3.html#section-165
      if (request.getProtocolVersion.compareTo(HttpVersion.HTTP_1_0) == 0) {
        response.setChunked(false)
        respond()
        response.setChunked(true)
      } else {
        respond()
      }
    }
  }

  /**
   * To respond chunks (http://en.wikipedia.org/wiki/Chunked_transfer_encoding):
   * 1. Call response.setChunked(true)
   * 2. Call respondXXX as many times as you want
   * 3. Lastly, call respondLastChunk()
   *
   * Headers are only sent on the first respondXXX call.
   */
  def respondLastChunk(): ChannelFuture = {
    if (!response.isChunked()) {
      printDoubleResponseErrorStackTrace()
    } else {
      val future = channel.write(HttpChunk.LAST_CHUNK)
      NoPipelining.if_keepAliveRequest_then_resumeReading_else_closeOnComplete(request, channel, future)

      // responded should be true here. Set chunk mode to false so that double
      // response error will be raised if the app try to respond more.
      response.setChunked(false)

      future
    }
  }

  //----------------------------------------------------------------------------

  /**
   * If contentType param is not given and Content-Type header is not set, it is
   * set to "application/xml" if text param is Node or NodeSeq, otherwise it is
   * set to "text/plain".
   */
  def respondText(text: Any, contentType: String = null): ChannelFuture = {
    val textIsXml = text.isInstanceOf[Node] || text.isInstanceOf[NodeSeq]

    // <br />.toString will create <br></br> which responds as 2 <br /> on some browsers!
    // http://www.scala-lang.org/node/492
    // http://www.ne.jp/asahi/hishidama/home/tech/scala/xml.html
    val respondedText =
      if (textIsXml) {
        if (text.isInstanceOf[Node])
          Xhtml.toXhtml(text.asInstanceOf[Node])
        else
          Xhtml.toXhtml(text.asInstanceOf[NodeSeq])
      } else {
        text.toString
      }

    if (!responded) {
      // Set content type automatically
      if (contentType != null)
        response.setHeader(CONTENT_TYPE, contentType)
      else if (!response.containsHeader(CONTENT_TYPE)) {
        if (textIsXml)
          response.setHeader(CONTENT_TYPE, "application/xml; charset=" + Config.config.request.charset)
        else
          response.setHeader(CONTENT_TYPE, "text/plain; charset=" + Config.config.request.charset)
      }
    }

    val cb = ChannelBuffers.copiedBuffer(respondedText, Config.requestCharset)
    if (response.isChunked) {
      writeHeaderIfFirstChunk()
      channel.write(new DefaultHttpChunk(cb))
    } else {
      // Content length is number of bytes, not characters!
      HttpHeaders.setContentLength(response, cb.readableBytes)
      response.setContent(cb)
      respond()
    }
  }

  //----------------------------------------------------------------------------

  /** Content-Type header is set to "text/html". */
  def respondHtml(any: Any): ChannelFuture = {
    respondText(any, "text/html; charset=" + Config.config.request.charset)
  }

  /** Content-Type header is set to "application/javascript". */
  def respondJs(any: Any): ChannelFuture = {
    respondText(any, "application/javascript; charset=" + Config.config.request.charset)
  }

  /** Content-Type header is set to "application/json". */
  def respondJsonText(any: Any): ChannelFuture = {
    respondText(any, "application/json; charset=" + Config.config.request.charset)
  }

  /**
   * Converts the given Scala object to JSON object, and responds it.
   * If you just want to respond a text with "application/json" as content type,
   * use respondJsonText(text).
   *
   * Content-Type header is set to "application/json".
   * "text/json" would make the browser download instead of displaying the content.
   * It makes debugging a pain.
   */
  def respondJson(obj: Any): ChannelFuture = {
    val json = Json.generate(obj)
    respondText(json, "application/json; charset=" + Config.config.request.charset)
  }

  /**
   * Converts the given Scala object to JSON object, wraps it with the given
   * JavaScript function name, and responds. If you already have a JSON text,
   * thus no conversion is needed, use respondJsonPText.
   *
   * Content-Type header is set to "application/javascript".
   */
  def respondJsonP(obj: Any, function: String): ChannelFuture = {
    val json = Json.generate(obj)
    val text = function + "(" + json + ");\r\n"
    respondJs(text)
  }

  /**
   * Wraps the text with the given JavaScript function name, and responds.
   *
   * Content-Type header is set to "application/javascript".
   */
  def respondJsonPText(text: Any, function: String): ChannelFuture = {
    respondJs(function + "(" + text + ");\r\n")
  }

  //----------------------------------------------------------------------------

  var renderedView: Any = null

  def layout = renderedView

  def respondInlineView(view: Any): ChannelFuture = {
    respondInlineView(view, layout _)
  }

  /** Content-Type header is set to "text/html" */
  def respondInlineView(view: Any, customLayout: () => Any): ChannelFuture = {
    renderedView = view
    val respondedLayout = customLayout.apply()
    if (respondedLayout == null)
      respondText(renderedView, "text/html; charset=" + Config.config.request.charset)
    else
      respondText(respondedLayout, "text/html; charset=" + Config.config.request.charset)
  }

  //----------------------------------------------------------------------------

  /**
   * Renders Scalate template file relative to src/main/view/scalate directory.
   * The current controller instance will be imported in the template as "helper".
   */
  def renderScalate(relPath: String) = Scalate.renderFile(this, relPath)

  /**
   * Renders Scalate template file with the path:
   * src/main/view/scalate/<the/given/controller/Class>.<templateType>
   *
   * @param controllerClass should be one of the parent classes of the current controller
   *                        because the current controller instance will be imported
   *                        in the template as "helper"
   * @param templateType "jade", "mustache", "scaml", or "ssp"
   */
  def renderScalate(controllerClass: Class[_], templateType: String): String = {
    val relPath = controllerClass.getName.replace('.', File.separatorChar) + "." + templateType
    renderScalate(relPath)
  }

  /**
   * Same as renderScalate(controllerClass, templateType),
   * where templateType is as configured in xitrum.json.
   */
  def renderScalate(controllerClass: Class[_]): String =
    renderScalate(controllerClass, Config.config.scalate)

  /**
   * Renders Scalate template file with the path:
   * src/main/view/scalate/<the/given/controller/Class>/_<fragment>.<templateType>
   *
   * @param controllerClass should be one of the parent classes of the current controller
   *                        because the current controller instance will be imported
   *                        in the template as "helper"
   * @param templateType "jade", "mustache", "scaml", or "ssp"
   */
  def renderFragment(controllerClass: Class[_], fragment: String, templateType: String): String = {
    val relPath = controllerClass.getName.replace('.', File.separatorChar) + File.separatorChar + "_" + fragment + "." + templateType
    renderScalate(relPath)
  }

  /**
   * Same as renderFragment(controllerClass, fragment, templateType),
   * where templateType is as configured in xitrum.json.
   */
  def renderFragment(controllerClass: Class[_], fragment: String): String =
    renderFragment(controllerClass, fragment, Config.config.scalate)

  /**
   * Responds Scalate template file with the path:
   * src/main/view/scalate/</class/name/of/the/controller/of/the/given/action>/<action name>.<templateType>
   *
   * @param templateType "jade", "mustache", "scaml", or "ssp"
   */
  def respondView(action: Action, customLayout: () => Any, templateType: String): ChannelFuture = {
    val nonNullActionMethod = if (action.method == null) Routes.lookupMethod(action.route) else action.method
    val controllerClass     = nonNullActionMethod.getDeclaringClass
    val actionName          = nonNullActionMethod.getName
    val relPath             = controllerClass.getName.replace('.', File.separatorChar) + File.separator + actionName + "." + templateType

    renderedView = renderScalate(relPath)
    val respondedLayout = customLayout.apply()
    if (respondedLayout == null)
      respondText(renderedView, "text/html; charset=" + Config.config.request.charset)
    else
      respondText(respondedLayout, "text/html; charset=" + Config.config.request.charset)
  }

  /**
   * Same as respondView(action, customLayout, templateType),
   * where templateType is as configured in xitrum.json.
   */
  def respondView(action: Action, customLayout: () => Any): ChannelFuture = {
    respondView(action, customLayout, Config.config.scalate)
  }

  /**
   * Same as respondView(action, customLayout, templateType),
   * where customLayout is from the controller's layout method.
   */
  def respondView(action: Action, templateType: String): ChannelFuture = {
    respondView(action, layout _, templateType)
  }

  /**
   * Same as respondView(action, customLayout, templateType),
   * where action is currentAction and customLayout is from the controller's layout method.
   */
  def respondView(templateType: String): ChannelFuture = {
    respondView(currentAction, templateType)
  }

  /**
   * Same as respondView(action, customLayout, templateType),
   * where customLayout is from the controller's layout method and
   * templateType is as configured in xitrum.json.
   */
  def respondView(action: Action): ChannelFuture = {
    respondView(action, layout _, Config.config.scalate)
  }

  /**
   * Same as respondView(action, customLayout, templateType),
   * where action is currentAction, customLayout is from the controller's layout method and
   * templateType is as configured in xitrum.json.
   */
  def respondView(): ChannelFuture = {
    respondView(currentAction, Config.config.scalate)
  }

  //----------------------------------------------------------------------------

  /** If Content-Type header is not set, it is set to "application/octet-stream" */
  def respondBinary(bytes: Array[Byte]): ChannelFuture = {
    respondBinary(ChannelBuffers.wrappedBuffer(bytes))
  }

  /** If Content-Type header is not set, it is set to "application/octet-stream" */
  def respondBinary(channelBuffer: ChannelBuffer): ChannelFuture = {
    if (response.isChunked) {
      writeHeaderIfFirstChunk()
      channel.write(new DefaultHttpChunk(channelBuffer))
    } else {
      if (!response.containsHeader(CONTENT_TYPE))
        response.setHeader(CONTENT_TYPE, "application/octet-stream")
      HttpHeaders.setContentLength(response, channelBuffer.readableBytes)
      response.setContent(channelBuffer)
      respond()
    }
  }

  /**
   * Sends a file using X-SendFile.
   * If Content-Type header is not set, it is guessed from the file name.
   *
   * @param path absolute or relative to the current working directory
   *
   * In some cases, the current working directory is not always the root directory
   * of the project (https://github.com/ngocdaothanh/xitrum/issues/47), you may
   * need to use xitrum.Config.root to calculate the correct absolute path from
   * a relative path.
   *
   * To sanitize the path, use xitrum.util.PathSanitizer.
   */
  def respondFile(path: String): ChannelFuture = {
    XSendFile.setHeader(response, path, true)
    respond()
  }

  /**
   * Sends a file from public directory in one of the entry (may be a JAR file)
   * in classpath.
   * If Content-Type header is not set, it is guessed from the file name.
   *
   * @param path Relative to an entry in classpath, without leading "/"
   */
  def respondResource(path: String): ChannelFuture = {
    XSendResource.setHeader(response, path, true)
    respond()
  }

  //----------------------------------------------------------------------------

  def respondWebSocket(text: Any): ChannelFuture = {
    channel.write(new TextWebSocketFrame(text.toString))
  }

  def respondWebSocket(channelBuffer: ChannelBuffer): ChannelFuture = {
    channel.write(new TextWebSocketFrame(channelBuffer))
  }

  //----------------------------------------------------------------------------

  def renderEventSource(data: Any, event: String = "message"): String = {
    val builder = new StringBuilder

    if (event != "message") {
      builder.append("event: ")
      builder.append(event)
      builder.append("\n")
    }

    val lines = data.toString.split("\n")
    val n = lines.length
    for (i <- 0 until n) {
      if (i < n - 1) {
        builder.append("data: ")
        builder.append(lines(i))
        builder.append("\n")
      } else {
        builder.append("data: ")
        builder.append(lines(i))
      }
    }

    builder.append("\r\n\r\n")
    builder.toString
  }

  /**
   * To respond event source, call this method as many time as you want.
   * Event Source response is a special kind of chunked response.
   * Data must be Must be  UTF-8.
   * See:
   * - http://sockjs.github.com/sockjs-protocol/sockjs-protocol-0.3.3.html#section-94
   * - http://dev.w3.org/html5/eventsource/
   */
  def respondEventSource(data: Any, event: String = "message"): ChannelFuture = {
    if (!responded) {
      response.setHeader(CONTENT_TYPE, "text/event-stream; charset=UTF-8")
      response.setChunked(true)
      respondText("\r\n")  // Send a new line prelude, due to a bug in Opera
    }
    respondText(renderEventSource(data, event))
  }

  //----------------------------------------------------------------------------

  def respondDefault404Page(): ChannelFuture = {
    XSendFile.set404Page(response, true)
    respond()
  }

  def respondDefault500Page(): ChannelFuture = {
    XSendFile.set500Page(response, true)
    respond()
  }

  //----------------------------------------------------------------------------

  def setClientCacheAggressively() {
    NotModified.setClientCacheAggressively(response)
  }

  def setNoClientCache() {
    NotModified.setNoClientCache(response)
  }

  //----------------------------------------------------------------------------

  /**
   * Prints the stack trace so that application developers know where to fix
   * the double response error.
   */
  private def printDoubleResponseErrorStackTrace(): ChannelFuture = {
    try {
      throw new IllegalStateException("Double response")
    } catch {
      case e: Exception =>
        logger.warn("Double response! This double response is ignored.", e)
    }
    null  // This may cause NPE on double response if the ChannelFuture result is used
  }
}
