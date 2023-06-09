package io.github.mastodonContentMover;

import jakarta.xml.bind.JAXBException;
import java.io.IOException;
import java.awt.Desktop;
import java.net.URI;
import java.util.concurrent.TimeUnit;

import social.bigbone.MastodonClient;
import social.bigbone.api.Scope;
import social.bigbone.api.entity.Application;
import social.bigbone.api.exception.BigBoneRequestException;
import social.bigbone.api.method.OAuthMethods;
import social.bigbone.api.entity.Token;

/**
*   Handles the process of authenticating a connection with a Mastodon instance, using an
*   OAuth callback facilitated by {@link io.github.mastodonContentMover.OAuthListener} — 
*   a minimal HTTP server that runs briefly on the local machine to receive a call from 
*   the browser on that machine to the {@code localhost}.
*   <br /><br />
*   Uses the singleton model - for current (and anticipated) use cases, there should not
*   be a need for more than one {@link io.github.mastodonContentMover.Authenticator} object
*   to be instantiated simultaneously. 
*   <br /><br />
*   Credentials for the client and the user account are saved and retrieved from the
*   filesystem in XML format using {@link io.github.mastodonContentMover.ClientCredentialStore} 
*   and {@link io.github.mastodonContentMover.UserCredentialStore} respectively.
*   <br /><br />
*   Credentials are not persisted when connecting via a custom port (maybe this could
*   be changed if there is a significant need for it, but it would require a rework of 
*   the data structure used to save credentials and the need for a custom port is 
*   expected to be a rare use case anyway).
*   <br /><br />
*   @see io.github.mastodonContentMover.OAuthListener
*   @author Tokyo Outsider
*   @since 0.01.00
*/
public class Authenticator {

   private static final String APPLICATION_NAME = "MastodonContentMover";
   private static final String DISPLAY_REDIRECT_URI = "urn:ietf:wg:oauth:2.0:oob";  // The URI that displays the access key to the user rather than redirecting to a website
   private static final String CALLBACK_REDIRECT_URI_PREFIX = "http://localhost:";  // First part of the URL OAuth should redirect back to (part before port number)
   private static final String CALLBACK_REDIRECT_URI_SUFFIX = "/mastodon-oauth-callback";  // Second part of the URL OAuth should redirect back to (part after port number)

   // TODO: Make these customizable parameters
   private static final Long CLIENT_CUSTOM_CONNECT_TIMEOUT = null;
   private static final Long CLIENT_CUSTOM_READ_TIMEOUT = Long.valueOf(60);   // Without this, media uploads of large files (e.g. videos) fail with a timeout
   private static final Long CLIENT_CUSTOM_WRITE_TIMEOUT = null;
   private static final int OAUTH_CALLBACK_MAX_SECONDS_WAITING = 180;   // Should document this in user docs / usage guide - TODO

   private static final int DEFAULT_PORT = 8081;

   private static Authenticator singletonInstance = null;
   private static String oAuthCallbackCode = null;
   private static String callbackRedirectUri = "";

   private Authenticator() {} 

   /**
   *   Obtains a reference to the currently instantiated {@link io.github.mastodonContentMover.Authenticator}
   *   singleton object, or instantiates one if that has not already been done.
   *   <br /><br />
   *   @return a reference to an {@link io.github.mastodonContentMover.Authenticator} object
   *   <br /><br />
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected static synchronized Authenticator getSingletonInstance() 
   {
      if (singletonInstance == null) 
      {
         singletonInstance = new Authenticator();
      }
      return singletonInstance;
   }


   /**
   *   Obtains an authenticated {@link social.bigbone.MastodonClient } object for the
   *   specified user account on the specified Mastodon instance, using saved credentials
   *   from previous use of the tool or by exhausting the various possible methods of 
   *   authentication in order of preference.
   *   <br /><br />
   *   The sequence of steps for OAuth authentication is as follows:
   *   <ol>
   *      <li>Authenticating the client with the Mastodon instance, to obtain a client
   *      key and secret</li>
   *      <li>Using the client key to obtain an OAuth URL</li>
   *      <li>Accessing the URL while logged in with the user account within the browwer
   *      to obtain an OAuth callback code, sent within the callback request after the
   *      user authorizes the tool</li>
   *      <li>Using the client key, secret and callback code to obtain an access token
   *      that can then be used with user-level API methods</li>
   *      <li>Using that access token to build an authenticated {@link social.bigbone.MastodonClient }
   *      object</li>
   *   </ol>
   *   <br /><br />
   *   This method:
   *   <ol>
   *      <li>Attempts to load an existing access token for a user from file (saved from
   *      previous use of the tool</li>
   *      <li>If a token is not available, attempts to load existing client credentials
   *      from file (saved from previous use of the tool)</li>
   *      <li>If client credentials are not available, registers the tool as a client to 
   *      obtain a client key and secret that can be used to obtain an access token via 
   *      OAuth authorization</li>
   *      <li>Uses the client key (loaded or obtained) to load an OAuth URL and obtain a 
   *      callback code, and then uses the client key and secret together with the 
   *      callback code to obtain an access token for the user account logged in
   *      in the browser</li>
   *      <li>Once the user-level access token is obtained (either loaded from file or
   *      via OAuth), it is used to build an authenticated  {@link social.bigbone.MastodonClient }</li>
   *   </ol>
   *   <br /><br />
   *   OAuth authorization is preferred as it is most likely to accommodate browser-based
   *   security measures such as two-factor authentication. There is currently no check
   *   within the tool to ensure that the Mastodon user account logged in to the given 
   *   instance within the browser when OAuth authorization is given matches the user 
   *   account provided as a command-line parameter to this tool (TODO: fix this).
   *   <br /><br />
   *   Both read and write access is requested irrespective of the operation being 
   *   performed, because the scope must be specified as part of the client registration
   *   for all operations the client will perform on the given Mastodon instance. If 
   *   write access was only requested on occasions when statuses were being reposted
   *   from an archive, it would be necessary to maintain two separate sets of client
   *   credentials for each instance (one with read access, and one with write access)
   *   or renew tokens each time the tool was run.
   *   <br /><br />
   *   OAuth authorization is implemented using a minimal HTTP server that runs briefly 
   *   on the local machine (while the user reviews the OAuth permission web page on the
   *   specified Mastodon instance and chooses whether or not to authorize the tool). 
   *   A {@code localhost} URL is passed on a call to the Mastodon {@code /oauth/authorize}
   *   endpoint, so the OAuth token is sent by the instance on callback to the temporary 
   *   HTTP server on the local machine. Once this is detected, the HTTP server is shut
   *   down.
   *   <br /><br />
   *   This HTTP server runs on the port specified in the constant {@code DEFAULT_PORT},
   *   set at time of writing to 8081. In the event that this port is already in use 
   *   on the local machine, it is possible to run this tool using a custom port that can
   *   be specified here as a parameter
   * 
   *   Credentials are not saved to or loaded from file when connecting via a custom 
   *   port (OAuth or another method of authentication must be used every time the tool
   *   is run). Approaching this differently would require quite a significant rework of
   *   the data structure used to save credentials, and the need for a custom port is 
   *   already expected to be a rare use case. Both user and client credential storage
   *   would need to be rewritten, as the port must be given as part of the callback URL
   *   submitted during client registration (you cannot register the client once and then
   *   re-use that client registration for OAuth user authorization irrespective of
   *   callback port.
   *   <br /><br />
   *   Alternative ways to obtain an access token include the {@code getUserAccessTokenWithPasswordGrant}
   *   method offered by the BigBone library's OAuthMethods.kt, and manual entry of an
   *   access token. Currently neither of these are supported, but they could and should
   *   be so the tool can still be used in the event of an issue with OAuth authorization
   *   (TODO). Obviously typing in an access token manually would be cumbersome; perhaps
   *   it could be a command-line parameter.
   *   <br /><br />
   *   @param instance the hostname or address of the Mastodon instance with which this
   *                   method should authenticate
   *   @param username the username for the account on the Mastodon instance with which this
   *                   method should authenticate
   *   @param customPort a valid port that can be bound on the local machine to briefly 
   *                     run an 
   *   <br /><br />
   *   @return an authenticated {@link social.bigbone.MastodonClient }
   *   <br /><br />
   *   @throws BigBoneRequestException (TODO: add more info here on when this happens)
   *   @throws JAXBException (TODO: add more info here on when this happens)
   *   @throws IOException (TODO: add more info here on when this happens)
   *   <br /><br />
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected synchronized MastodonClient getClient(String instance, String username, Integer customPort) throws JAXBException, IOException, BigBoneRequestException {  // Should add scope as a variable, so we can request just READ for saving.

      int port = DEFAULT_PORT;
      if (customPort != null) {
         port = customPort.intValue();
         if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "Authenticator: Custom port specified (" + port + ") \n");  }
      }
      callbackRedirectUri = CALLBACK_REDIRECT_URI_PREFIX + port + CALLBACK_REDIRECT_URI_SUFFIX;

      // First, try to get the access key for this user on this instance
      // Specifying a custom port will cause an error with existing tokens - let's only use the store if we're using the default port, and always handle the custom port as an exception
      String accessToken = null;
      if (customPort == null) {
         accessToken = UserCredentialStore.getSingletonInstance().getAccessToken(instance, username);
      }

      if (accessToken != null) 
      {
         MastodonClient.Builder b = new MastodonClient.Builder(instance).accessToken(accessToken);
         if (CLIENT_CUSTOM_CONNECT_TIMEOUT != null) {   b.setConnectTimeoutSeconds(CLIENT_CUSTOM_CONNECT_TIMEOUT.longValue());   }
         if (CLIENT_CUSTOM_READ_TIMEOUT != null) {   b.setReadTimeoutSeconds(CLIENT_CUSTOM_READ_TIMEOUT.longValue());   }
         if (CLIENT_CUSTOM_WRITE_TIMEOUT != null) {   b.setWriteTimeoutSeconds(CLIENT_CUSTOM_WRITE_TIMEOUT.longValue());   }
         return b.build();
      }
      else  // accessToken is null
      {
         if (Mover.showDebug()) {  System.out.println("Unknown user for this instance when connecting via OAuth from this port");  }

         // We don't have an access token for this user, so we have to use the client key and secret together with user credentials to create an access token
         String clientKey = null;
         String clientSecret = null;

         // Specifying a custom port will cause an error with existing tokens - let's only use the store if we're using the default port, and always handle the custom port as an exception
         ClientCredentialSet set = null;
         if (customPort == null) {
            set = ClientCredentialStore.getSingletonInstance().getCredentials(instance);
         }

         if (set != null)  // We already have a client key and secret saved for this instance
         {
            clientKey = set.getClientKey();
            clientSecret = set.getClientSecret();
         }
         else
         {
            if (Mover.showDebug()) {  System.out.println("Client not registered with this instance");  }

            // We don't have a client key or secret for this instance, so we have to register the app with the instance first
            if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "Trying to connect \n");  }
            MastodonClient client = new MastodonClient.Builder(instance).build(); // Temporary client to register the app
            try
            {
               if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "Registering app \n");  }
               Application application = client.apps().createApp(
                  APPLICATION_NAME,
                  callbackRedirectUri,
                  new Scope(Scope.Name.READ, Scope.Name.WRITE),   // We only need READ to save but we will need WRITE to post.
                  ""  // *** Add url here with info about app??
               ).execute();
               clientKey = application.getClientId();
               clientSecret = application.getClientSecret();
            }
            catch (BigBoneRequestException bbre)   // TODO: Improve this handling
            {
               if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "Failed while attempting to register app");  }
               if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "Message: " + bbre.getMessage());   }
               if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "HTTP Code: " + bbre.getHttpStatusCode());   }
               bbre.printStackTrace();
            }

            if ((clientKey == null) || (clientSecret == null))
            {
               // We didn't have an access token, we tried to register the app and didn't receive valid credentials... what to do?!
               System.out.println(Mover.getErrorMessagePrefix() + "Failed to obtain client credentials \n");
               return null;  // exit early TODO: Handle as an exception instead
            }
            
            // We received credentials
            // Store them! (providing we're not using a custom port)
            if (customPort == null) {
               if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "Storing client credentials \n");  }
               ClientCredentialStore.getSingletonInstance().addCredentials(instance, clientKey, clientSecret);
            }

         } 

         // Now we have our client credentials - let's try to get an access token
         if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "Trying to connect \n");  }
         MastodonClient client = new MastodonClient.Builder(instance).build();   // Temporary client to create an access token
         OAuthMethods oauthMethodsObject = new OAuthMethods(client);
         try 
         {
            OAuthListener oal = OAuthListener.getInstance(port);   // Start our mini HTTP server on the port specified
            String oAuthUrl = oauthMethodsObject.getOAuthUrl(clientKey, new Scope(Scope.Name.READ, Scope.Name.WRITE), callbackRedirectUri);  // Obtain our URL from the Mastodon instance
            Desktop.getDesktop().browse(URI.create(oAuthUrl));  // Load the URL in the browser

            long waiting = 0;
            while ((oAuthCallbackCode == null) && (waiting++ < OAUTH_CALLBACK_MAX_SECONDS_WAITING))   // Wait for the user to authorize the tool in their browser
            {
               try { TimeUnit.SECONDS.sleep(1); }
               catch (InterruptedException ie) {  }
            }
            oal.shutdown();

            if (oAuthCallbackCode != null)   // Not thread safe if the method isn't synchronized
            {  
               if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "oAuthCallbackCode isn't null - getting token \n");  }

               if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "Authenticator#getClient: clientKey received");  }
               if (false) {  System.out.println(Mover.getDebugPrefix() + "Authenticator#getClient: clientKey: " + clientKey);  }

               if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "Authenticator#getClient: clientSecret received");  }
               if (false) {  System.out.println(Mover.getDebugPrefix() + "Authenticator#getClient: clientSecret: " + clientSecret);  }

               if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "Authenticator#getClient: callbackRedirectUri: " + callbackRedirectUri);  }

               if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "Authenticator#getClient: oAuthCallbackCode received");  }
               if (false) {  System.out.println(Mover.getDebugPrefix() + "Authenticator#getClient: oAuthCallbackCode: " + oAuthCallbackCode);  }

               String token = oauthMethodsObject.getUserAccessTokenWithAuthorizationCodeGrant(clientKey, clientSecret, callbackRedirectUri, oAuthCallbackCode).execute().getAccessToken();
               if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "Got token. Connecting... \n");  }

               MastodonClient.Builder b = new MastodonClient.Builder(instance).accessToken(token);
               if (CLIENT_CUSTOM_CONNECT_TIMEOUT != null) {   b.setConnectTimeoutSeconds(CLIENT_CUSTOM_CONNECT_TIMEOUT.longValue());   }
               if (CLIENT_CUSTOM_READ_TIMEOUT != null) {   b.setReadTimeoutSeconds(CLIENT_CUSTOM_READ_TIMEOUT.longValue());   }
               if (CLIENT_CUSTOM_WRITE_TIMEOUT != null) {   b.setWriteTimeoutSeconds(CLIENT_CUSTOM_WRITE_TIMEOUT.longValue());   }
               client = b.build();

               if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "Verifying token against user account \n");  }

               String connectedUsername = client.accounts().verifyCredentials().execute().getUsername();
               if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "Connected username: " + connectedUsername + " \n");  }
               if(!connectedUsername.toLowerCase().equals(username.toLowerCase())) {
                  System.out.println(Mover.getErrorMessagePrefix() + "Account used in browser does not match that specified as a parameter. \n\nPlease check you are logged in to the correct account in your browser. \n");  // TODO: Make this a constant and/or handle with an exception rather than a null
                  return null;  // exit early
               }
          

               if (customPort == null) {  // Only save the token if we're not using a custom port

                  if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "Saving token \n");  }
                  UserCredentialStore.getSingletonInstance().addCredentials(instance, username, token);
               }


               oAuthCallbackCode = null; // For next time
               return client;
            }
            else 
            {
               // We still don't have a token.... 
               System.out.println(Mover.getErrorMessagePrefix() + "Timed out while trying to obtain access token \n");  
               return null;  // exit early TODO: Handle this as an exception instead
            }
         }
         catch (IOException ioe)    // TODO: Improve this handling
         {
            if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "Failed while attempting to authenticate");  }
            if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "Message: " + ioe.getMessage());  }
            ioe.printStackTrace();
            return null; // TODO: Handle as an exception instead
         }
      }
   }

   /**
   *   Passes an OAuth callback code received by an instance of {@link io.github.mastodonContentMover.OAuthListener}
   *   (which runs a separate thread) to this object, so it can then be collected by {@link io.github.mastodonContentMover.Authenticator#getClient(String instance, String username, Integer customPort)}
   *   to perform further authentication tasks.
   *   <br /><br />
   *   @param code the OAuth callback code
   *   <br /><br />
   *   @see io.github.mastodonContentMover.OAuthListener
   *   @see io.github.mastodonContentMover.Authenticator#getClient(String instance, String username, Integer customPort)
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected void oAuthCallback(String code) {
      if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "Authenticator#oAuthCallback: OAuthListener called back to Authenticator with OAuth callback code");  }
      if (false) {  System.out.println(Mover.getDebugPrefix() + "Authenticator#oAuthCallback: Code: " + code);  }
      oAuthCallbackCode = code;
   }

}