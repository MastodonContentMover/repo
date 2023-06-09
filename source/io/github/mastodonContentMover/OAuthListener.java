package io.github.mastodonContentMover;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.Headers;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

// Note - synchronized methods will be synchronized on the singleton object because there is only one instance

/**
*   Operates a minimal HTTP server on the local machine for the duration of OAuth
*   authorization of the tool by a Mastodon instance, passing the callback code
*   collected via an OAuth callback (from a browser on that machine) to the instantiated
*   {@link io.github.mastodonContentMover.Authenticator} singletone object.
*   <br /><br />
*   The HTTP server is then shut down and the code is used by the {@link io.github.mastodonContentMover.Authenticator}
*   to obtain an access token that grants access to user-level API methods on the
*   Mastodon instance.
*   <br /><br />
*   By default, the HTTP server is bound to port 8081 (this is specified within {@link io.github.mastodonContentMover.Authenticator})
*   but can be bound to a different port as needed. If a custom port is used, credentials
*   are not saved.
*   <br /><br />
*   Uses the singleton model, as only one {@link io.github.mastodonContentMover.OAuthListener}
*   should be instantiated at a time. Synchronized methods will be synchronized on the 
*   singleton object (because there is only ever one).
*   <br /><br />
*   Also defines inner class {@link io.github.mastodonContentMover.OAuthListener.OAuthRequestHandler}
*   that implements the {@link com.sun.net.httpserver.HttpHandler} interface to handle
*   inbound HTTP requests in a separate thread. It is within this inner class that the
*   HTML to be displayed in the browser after OAuth authorization is defined.
*   <br /><br />
*   @see io.github.mastodonContentMover.Authenticator 
*   @author Tokyo Outsider
*   @since 0.01.00
*/
public class OAuthListener {

   private static final int SHUTDOWN_TIME_SECONDS = 3;

   private static OAuthListener singletonInstance = null;
   private HttpServer server = null;
   private ExecutorService executorService = null;
   private int port = -1;

   private OAuthListener(int p) throws IOException {
      this.port = p;
      server = HttpServer.create(new InetSocketAddress("localhost", this.port), 0); 
      server.createContext("/mastodon-oauth-callback", new OAuthRequestHandler());
      executorService = Executors.newSingleThreadExecutor();
      server.setExecutor(executorService);
      server.start();
      if (Mover.showDebug()) {  System.out.println("OAuthListener started on port " + this.port);  }
   } 

   /**
   *   Obtains a reference to the currently instantiated {@link io.github.mastodonContentMover.OAuthListener}
   *   singleton object, or instantiates one if that has not already been done using the
   *   port specified.
   *   <br /><br />
   *   If the currently instantiated singleton is bound to a port different to the one
   *   specified, it is shutdown and restarted on the specified port. 
   *   <br /><br />
   *   @param port the TCP port on the local machine on which to bind the singleton {@link io.github.mastodonContentMover.OAuthListener}
   *   @return a singleton instance of {@link io.github.mastodonContentMover.OAuthListener} bound to the port specified
   *   <br /><br />
   *   @throws IOException (TODO: add more info here on when this happens)
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected static synchronized OAuthListener getInstance(int port) throws IOException {
      if (singletonInstance == null) {
          singletonInstance = new OAuthListener(port);
      }
      else {
         if (singletonInstance.port != port) {
            singletonInstance.shutdown();
            singletonInstance = new OAuthListener(port);
         }
      }
      return singletonInstance;
   }
  
   /**
   *   Halts the HTTP server, allowing some time for the shutdown process to complete and
   *   then releasing resources that had been allocated to it.
   *   <br /><br />
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected synchronized void shutdown() {
      server.stop(SHUTDOWN_TIME_SECONDS);

// 5/21 I don't think we need these lines as HttpServer.stop() already blocks until the task is done - TODO: Remove these if no issues
//      try {
//         TimeUnit.SECONDS.sleep(SHUTDOWN_TIME_SECONDS);
//      }
//      catch (InterruptedException ie) {   }

      server = null;
      executorService.shutdown();
      executorService = null;
   }

   private class OAuthRequestHandler implements HttpHandler {

      public void handle(HttpExchange exchange) throws IOException {
         
         // We want to find everything after the "code=" and before another "="
         String code = exchange.getRequestURI().getRawQuery().split("code=")[1];
         code = code.split("=")[0];  // Just to be sure

         OutputStream outputStream = exchange.getResponseBody();
         String htmlResponse = "<html><body><h3>Please close this window and check the command line.</h3></body></html>";
         exchange.sendResponseHeaders(200, htmlResponse.length());
         outputStream.write(htmlResponse.getBytes());
         outputStream.flush();
         outputStream.close();

         if (Mover.showDebug()) {  System.out.println("OAuthRequestHandler#handle: Sending back OAuth callback code");   }
         if (false) {  System.out.println("OAuthRequestHandler#handle: Code: " + code);   }
         Authenticator.getSingletonInstance().oAuthCallback(code);
      }
   }


}