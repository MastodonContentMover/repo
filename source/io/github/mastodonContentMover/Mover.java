package io.github.mastodonContentMover;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;
import java.util.Locale;
import java.time.format.DateTimeFormatter;
import java.time.ZonedDateTime;
import java.time.Duration;
import java.io.File;
import java.net.URL;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;

//   JAXB for persistence
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlTransient;
import jakarta.xml.bind.annotation.XmlSeeAlso;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;

//   Bigbone library to connect with Mastodon
import social.bigbone.MastodonClient;
import social.bigbone.api.Pageable;
import social.bigbone.api.Range;
import social.bigbone.api.Scope;
import social.bigbone.api.entity.Application;
import social.bigbone.api.entity.Instance;
import social.bigbone.api.entity.Status;
import social.bigbone.api.entity.Status.Visibility;
import social.bigbone.api.entity.MediaAttachment;
import social.bigbone.api.entity.data.Focus;
import social.bigbone.api.exception.BigBoneRequestException;


//   Explicit imports added so Bigbone dependencies are included
import kotlin.jvm.internal.Intrinsics;   // Needs kotlin-runtime-1.2.71.jar or kotlin-stdlib-1.8.10.jar
import okhttp3.*;   // from okhttp-4.10.0.jar, needs okio.Buffer (see below)
import okio.*;   // from okio-2.9.0.jar, needs kotlin.collections.* (see below)
import kotlin.collections.*;   // from kotlin-stdlib-1.8.10.jar


/**
*   Bootstraps application, parses command-line parameters, determines which function
*   was requested by the user, and then performs that function.
*   <br /><br />
*   Currently two functions are available: {@code save}, to download and save statuses 
*   for a given user account on a specified Mastodon instance to an XML-based archive on
*   the local machine, and {@code post}, to repost them to a user account on a Mastodon
*   instance.
*   <br /><br />
*   At a high level, saving to an archive does the following:
*   <ol>
*      <li>Parses and validates command-line parameters</li>
*      <li>Connects to the specified user account and Mastodon instance (obtaining client
*      and user authorization where necessary, and saving the relevant credentials to 
*      file for future use)</li>
*      <li>Downloads the Mastodon statuses from that user's own timeline in a series
*      of pages (backwards from the most recent), which obtains much {@code status}
*      data but does not include the unformatted {@code StatusSource} text needed to 
*      repost the status elsewhere, nor does it include media files.</li>
*      <li>Works back through the downloaded statuses (to download the {@code StatusSource}
*      and media files for each {@code status}, and to identify any self-thread 
*      relationships that need to be preserved) and saves them as a {@link io.github.mastodonContentMover.Post} 
*      to a {@link io.github.mastodonContentMover.PostArchive} named according to the
*      {@code archiveName} parameter (which holds this data in memory and also persists
*      it to a set of XML files, using JAXB, on the local filesystem within a directory
*      named to match the name of the archive).</li> 
*   </ol>
*   Posting from an archive does the following:
*   <ol>
*      <li>Parses and validates command-line parameters</li>
*      <li>Connects to the specified user account and Mastodon instance (obtaining client
*      and user authorization where necessary and saving the relevant credentials to 
*      file for future use)</li>
*      <li>Populates a {@link io.github.mastodonContentMover.PostArchive} by loading the
*      data for its {@link io.github.mastodonContentMover.Post} objects from the set of
*      XML files on the local filesystem.</li>
*      <li>Iterates through the archive, uploading any media attached to each {@link io.github.mastodonContentMover.Post} 
*      to the specified Mastodon instance, removing meaningful symbols such as the at-mark
*      for mentions or hashtag that would trigger spurious notifications from the text, 
*      and then submitting it as a new {@code status} (and as a reply to a previously 
*      reposted {@code status} where it is part of a self-thread).</li>
*      <li>Re-bookmarks or re-pins reposted statuses that were bookmarked or pinned
*      by the saving user when the archive was created, and where this is specified</li>
*   </ol>
*   To prevent a deluge of API requests to Mastodon instances, all current default
*   API rate limits are honoured conservatively, with a small additional delay. It is
*   also possible to increase this delay using a command-line parameter.
*   <br /><br />
*   The main, required command-line options are as follows:
*   <ul>
*      <li>either {@code save} or {@code post}</li>
*      <li>{@code -username}</li>
*      <li>{@code -instance}</li>
*      <li>{@code -archiveName}</li>
*   </ul>
*   Additional, optional command-line options that are currently implemented are as follows:
*   <ul>
*      <li>{@code -showdebug}</li>
*      <li>{@code -preserveHashtags}</li>
*      <li>{@code -bookmarkedOnly}</li>
*      <li>{@code -from} (only when using {@code post})</li>
*      <li>{@code -until} (only when using {@code post})</li>
*      <li>{@code -extraThrottle}</li>
*      <li>{@code -customPort}</li>
*   </ul>
*   <br /><br />
*   @author Tokyo Outsider
*   @since 0.01.00
*/
public class Mover {


   private static final String DATA_DIRECTORY = "data";   // TODO: Could perhaps make this a parameter later
   private static final int UNPROCESSABLE_CONTENT_HTTP_ERROR = 422;
   private static final int DEFAULT_POST_LENGTH_LIMIT_CHARACTERS = 500;
   private static final int DEFAULT_MEDIA_ATTACHMENT_LIMIT_COUNT = 4;
   private static final int DEFAULT_URL_CHARACTER_COUNT = 23;

   // Interface constants
   private static final String ERROR_MESSAGE_PREFIX = "ERROR: ";
   private static final String DEBUG_PREFIX = "DEBUG: ";

   private static final String INTERFACE_TIMESTAMP_FORMAT = "EEEE MMMM d uuuu h:mm:ssa zzzz";

   private static final String MALFORMED_PARAMETER_PREFIX = "Parameter error: ";
   private static final String MALFORMED_PARAMETER_SUFFIX = "Please check the parameters entered and try again. ";
   private static final String MALFORMED_PARAMETER_ALREADY_DEFINED_SUFFIX = "can only be specified once. ";
   private static final String MALFORMED_PARAMETER_NO_VALUE_PREFIX = "No value was given for parameter ";
   private static final String MALFORMED_PARAMETER_INVALID_VALUE_PREFIX = "Invalid value was given for parameter ";
   private static final String MALFORMED_PARAMETER_UNKNOWN_OPERATION_PREFIX = "Unknown operation specified ";

   private static final int INTERFACE_STATUS_TEXT_TRUNCATE_LENGTH = 70;

   private static final int DURATION_ESTIMATE_MARGIN_PERCENT = 5;   // Add 5% onto all duration estimates

   // API settings
   private static final int STATUSES_PER_PAGE = 40;   // Current maximum
   private static final int API_THROTTLE_SECONDS = 5;  // Actual limit is 1
   private static final int API_THROTTLE_STATUS_PAGE_SECONDS = 5;  // Actual limit is 1
   private static final int API_THROTTLE_NEW_NONPUBLIC_POST_SECONDS = 5;  // Actual limit is 1
   private static final int API_THROTTLE_NEW_PUBLIC_POST_SECONDS = 30;  // Actual limit is 1
   private static final int API_THROTTLE_MEDIA_UPLOAD_SECONDS = 120;  // Actual limit is 60 seconds for uploading media

   // Used to decide whether or not to download a thumbnail
   private static final String MASTODON_MEDIA_TYPE_VIDEO = "video";

   // Parsed from parameters in parseParameters(String[] args)
   private static String parameterInstance = null;
   private static String parameterUsername = null;
   private static String parameterOperation = null;
   private static String parameterArchiveName = null;
   private static boolean parameterShowDebug = false;   // TODO: Specify in docs to call this as the first parameter (to make sure debug messages when parsing other parameters are printed)
   private static Boolean parameterPreserveHashtags = null; 
   private static Boolean parameterPreserveBookmarks = null; 
   private static Boolean parameterPreservePins = null; 
   private static Boolean parameterBookmarkedOnly = null; 
   private static String parameterFrom = null;
   private static String parameterUntil = null;
   private static Integer parameterExtraThrottleSeconds = null;
   private static Integer parameterCustomPort = null;
   private static Boolean parameterSuppressPublic = null; 

   // Parameter names must be specified here as lower case so case-insensitive matching works
   private static final String PARAMETER_NAME_INSTANCE = "instance";
   private static final String PARAMETER_NAME_USERNAME = "username";
   private static final String PARAMETER_NAME_ARCHIVE_NAME = "archivename";
   private static final String PARAMETER_NAME_SHOW_DEBUG = "showdebug";
   private static final String PARAMETER_NAME_PRESERVE_HASHTAGS = "preservehashtags";
   private static final String PARAMETER_NAME_PRESERVE_BOOKMARKS = "preservebookmarks";   // TODO: not implemented in parseParameters(String[] args) yet
   private static final String PARAMETER_NAME_PRESERVE_PINS = "preservepins";   // TODO: not implemented in parseParameters(String[] args) yet
   private static final String PARAMETER_NAME_BOOKMARKED_ONLY = "bookmarkedonly";
   private static final String PARAMETER_NAME_FROM = "from";   // takes an ISO 8601 date time
   private static final String PARAMETER_NAME_UNTIL = "until";   // takes an ISO 8601 date time
   private static final String PARAMETER_NAME_EXTRA_THROTTLE_SECONDS = "extrathrottle"; 
   private static final String PARAMETER_NAME_CUSTOM_PORT = "customport"; 
   private static final String PARAMETER_NAME_SUPPRESS_PUBLIC = "suppresspublic"; 
   private static final String OPERATION_NAME_SAVE = "save";
   private static final String OPERATION_NAME_POST = "post";


   /**
   *   Initializes application, accepting command-line parameters, calling
   *   {@link #parseParameters(String[] args) parseParameters} to parse them into global
   *   variables and then calling the requested procedure accordingly.
   *   <br /><br />
   *   @param args space-delimited command line parameters as a primitive array of {@link java.lang.String}
   *               objects
   *   <br /><br />
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   public static void main(String[] args) {


      System.out.println("\n********** Mastodon Content Mover **********\n");   // TODO: Might be nice to add a timestamp output at startup


      System.out.println("Starting at " + DateTimeFormatter.ofPattern(INTERFACE_TIMESTAMP_FORMAT).withLocale(Locale.ENGLISH).format(ZonedDateTime.now()) + " \n");

      try {
         parseParameters(args);   // Ideally this is called before we print any debug, because it parses the parameter that specifies whether debug is printed
      } catch (Exception e) {
         System.out.println(Mover.getErrorMessagePrefix() + "Parsing parameters failed with the following message: ");
         System.out.println(Mover.getErrorMessagePrefix() + e.getMessage());
         Mover.printEndTimestamp();
         System.exit(-1);
      }


      // ******************************************************************************************
      // ****************************** Uncomment below to run test harness
      // testSegmentText();
      // System.exit(0); 
      // ******************************************************************************************


      if (parameterOperation.equals(OPERATION_NAME_SAVE)) {

         try {
            save();
         }
         catch (Exception e) {
            System.out.println(Mover.getErrorMessagePrefix() + "Archiving failed with the following message: ");
            System.out.println(Mover.getErrorMessagePrefix() + e.getMessage());
            if (e instanceof BigBoneRequestException) {
               System.out.println(Mover.getErrorMessagePrefix() + "HTTP Status Code: " + ((BigBoneRequestException) e).getHttpStatusCode());
            }
            if (Mover.showDebug()) {  e.printStackTrace();  }
            Mover.printEndTimestamp();
            System.exit(-1);
         }
      }
      else if (parameterOperation.equals(OPERATION_NAME_POST)) {

         try {
            post();
         }
         catch (Exception e) {
            System.out.println(Mover.getErrorMessagePrefix() + "Posting failed with the following message: ");
            System.out.println(Mover.getErrorMessagePrefix() + e.getMessage());
            if (e instanceof BigBoneRequestException) {
               System.out.println(Mover.getErrorMessagePrefix() + "HTTP Status Code: " + ((BigBoneRequestException) e).getHttpStatusCode());
            }
            if (Mover.showDebug()) {  e.printStackTrace();  }
            Mover.printEndTimestamp();
            System.exit(-1);
          }
      } 

      Mover.printEndTimestamp();

   } // End of public static void main(String[] args)

   // Splitting this into a separate method so I can call it when exiting due to errors. 
   // TODO: Make sure exiting due to errors only happens within this class
   private static void printEndTimestamp() {
      System.out.println("Ending at " + DateTimeFormatter.ofPattern(INTERFACE_TIMESTAMP_FORMAT).withLocale(Locale.ENGLISH).format(ZonedDateTime.now()) + " \n");
   }

   // I wrote the Javadoc, then realised this method didn't need to be "protected"
   /**
   *   Parses parameters, including both those with no "-" prefix (specifying the 
   *   operation) and those with a "-" prefix (specifying options), populates the global
   *   variables for those parameters with the values specified, checks their contents 
   *   are valid and populates them with default values where they are not specified and
   *   defaults are needed.
   *   <br /><br />
   *   @param args an array of {@link java.lang.String} objects each containing a
   *               space-separated parameter passed on the command line 
   *   @throws IllegalArgumentException when parameters are invalid or specified more
   *                                    than once (except for showDebug, but that may
   *                                    change)
   *   <br /><br />
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   private static void parseParameters(String[] args) throws IllegalArgumentException {

      for (String argument : args) {

         String a[] = argument.split("-");  // TODO: Make this String a constant?
         if (a.length > 1) {
           
            String b[] = a[1].split(":");  // TODO: Make this String a constant?
            if (b.length > 1) {

               if (b[0].toLowerCase().equals(PARAMETER_NAME_SHOW_DEBUG)) {  // Can't switch on a boolean. TODO: Should perhaps be a Boolean object, for consistency

                  if (b[1].toLowerCase().equals("yes")) {
                     parameterShowDebug = true;
                  }
                  else if (b[1].toLowerCase().equals("no")) {
                     parameterShowDebug = false;
                  }
                  else {
                     throw new IllegalArgumentException(MALFORMED_PARAMETER_PREFIX + MALFORMED_PARAMETER_INVALID_VALUE_PREFIX + "'" + b[0] + "'. " + MALFORMED_PARAMETER_SUFFIX); 
                  }
                  System.out.println("Parameter '" + b[0] + "' set to '" + b[1] + "' \n");
               }
               else if (b[0].toLowerCase().equals(PARAMETER_NAME_PRESERVE_HASHTAGS)) {  // Can't switch on boolean

                  if (parameterPreserveHashtags == null) {
                     if (b[1].toLowerCase().equals("yes")) {
                        parameterPreserveHashtags = Boolean.valueOf(true);
                     }
                     else if (b[1].toLowerCase().equals("no")) {
                        parameterPreserveHashtags = Boolean.valueOf(false);
                     }
                     else {
                        throw new IllegalArgumentException(MALFORMED_PARAMETER_PREFIX + MALFORMED_PARAMETER_INVALID_VALUE_PREFIX + "'" + b[0] + "'. " + MALFORMED_PARAMETER_SUFFIX); 
                     }
                     System.out.println("Parameter '" + b[0] + "' set to '" + b[1] + "' \n");

                  } else {
                      throw new IllegalArgumentException(MALFORMED_PARAMETER_PREFIX + "'" + b[0] + "' " + MALFORMED_PARAMETER_ALREADY_DEFINED_SUFFIX + MALFORMED_PARAMETER_SUFFIX); 
                  }
               }
               else if (b[0].toLowerCase().equals(PARAMETER_NAME_BOOKMARKED_ONLY)) {  // Can't switch on boolean

                  if (parameterBookmarkedOnly == null) {   // TODO: Turn this repeated logic into a convenience method, and do the same for the logic repeatedly used in the switch statements
                     if (b[1].toLowerCase().equals("yes")) {
                        parameterBookmarkedOnly = Boolean.valueOf(true);
                     }
                     else if (b[1].toLowerCase().equals("no")) {
                        parameterBookmarkedOnly = Boolean.valueOf(false);
                     }
                     else {
                        throw new IllegalArgumentException(MALFORMED_PARAMETER_PREFIX + MALFORMED_PARAMETER_INVALID_VALUE_PREFIX + "'" + b[0] + "'. " + MALFORMED_PARAMETER_SUFFIX); 
                     }
         
                     System.out.println("Parameter '" + b[0] + "' set to '" + b[1] + "' \n");

                  } else {
                      throw new IllegalArgumentException(MALFORMED_PARAMETER_PREFIX + "'" + b[0] + "' " + MALFORMED_PARAMETER_ALREADY_DEFINED_SUFFIX + MALFORMED_PARAMETER_SUFFIX); 
                  }
               }
               else if (b[0].toLowerCase().equals(PARAMETER_NAME_SUPPRESS_PUBLIC)) {  // Can't switch on boolean

                  if (parameterSuppressPublic == null) {   // TODO: Turn this repeated logic into a convenience method, and do the same for the logic repeatedly used in the switch statements
                     if (b[1].toLowerCase().equals("yes")) {
                        parameterSuppressPublic = Boolean.valueOf(true);
                     }
                     else if (b[1].toLowerCase().equals("no")) {
                        parameterSuppressPublic = Boolean.valueOf(false);
                     }
                     else {
                        throw new IllegalArgumentException(MALFORMED_PARAMETER_PREFIX + MALFORMED_PARAMETER_INVALID_VALUE_PREFIX + "'" + b[0] + "'. " + MALFORMED_PARAMETER_SUFFIX); 
                     }
         
                     System.out.println("Parameter '" + b[0] + "' set to '" + b[1] + "' \n");

                  } else {
                      throw new IllegalArgumentException(MALFORMED_PARAMETER_PREFIX + "'" + b[0] + "' " + MALFORMED_PARAMETER_ALREADY_DEFINED_SUFFIX + MALFORMED_PARAMETER_SUFFIX); 
                  }
               }
               else {
                  switch (b[0].toLowerCase()) {
                     case PARAMETER_NAME_INSTANCE:
                        if (parameterInstance == null) {
                           parameterInstance = b[1];
                           System.out.println("Parameter '" + b[0] + "' set to '" + b[1] + "' \n");
                        } else {
                           throw new IllegalArgumentException(MALFORMED_PARAMETER_PREFIX + "'" + b[0] + "' " + MALFORMED_PARAMETER_ALREADY_DEFINED_SUFFIX + MALFORMED_PARAMETER_SUFFIX); 
                        }
                        break;
   
                     case PARAMETER_NAME_USERNAME:
                        if (parameterUsername == null) {
                           parameterUsername = b[1];
                           System.out.println("Parameter '" + b[0] + "' set to '" + b[1] + "' \n");
                        } else {
                           throw new IllegalArgumentException(MALFORMED_PARAMETER_PREFIX + "'" + b[0] + "' " + MALFORMED_PARAMETER_ALREADY_DEFINED_SUFFIX + MALFORMED_PARAMETER_SUFFIX); 
                        }
                        break;
   
                     case PARAMETER_NAME_ARCHIVE_NAME:
                        if (parameterArchiveName == null) {
                           parameterArchiveName = b[1];

                           // Check the archive name specified contains only upper or lowercase latin characters, digits or underscores (TODO: make sure this is documented)
                           if (!parameterArchiveName.matches("[\\w|_]+")) {  
                              throw new IllegalArgumentException(MALFORMED_PARAMETER_PREFIX + MALFORMED_PARAMETER_INVALID_VALUE_PREFIX + "'" + PARAMETER_NAME_ARCHIVE_NAME + "'. " + MALFORMED_PARAMETER_SUFFIX); 
                           }

                           System.out.println("Parameter '" + b[0] + "' set to '" + b[1] + "' \n");

                        } else {
                           throw new IllegalArgumentException(MALFORMED_PARAMETER_PREFIX + "'" + b[0] + "' " + MALFORMED_PARAMETER_ALREADY_DEFINED_SUFFIX + MALFORMED_PARAMETER_SUFFIX); 
                        }
                        break;

                     case PARAMETER_NAME_EXTRA_THROTTLE_SECONDS:
                        if (parameterExtraThrottleSeconds == null) {
                           parameterExtraThrottleSeconds = Integer.valueOf(b[1]);
                           if (parameterExtraThrottleSeconds.intValue() < 0) { parameterExtraThrottleSeconds = 0; }  // TODO: Should throw an exception here instead
                           System.out.println("Parameter '" + b[0] + "' set to '" + b[1] + "' \n");
                        } else {
                           throw new IllegalArgumentException(MALFORMED_PARAMETER_PREFIX + "'" + b[0] + "' " + MALFORMED_PARAMETER_ALREADY_DEFINED_SUFFIX + MALFORMED_PARAMETER_SUFFIX); 
                        }
                        break;

                     case PARAMETER_NAME_CUSTOM_PORT:
                        if (parameterCustomPort == null) {
                           parameterCustomPort = Integer.valueOf(b[1]);
                           System.out.println("Parameter '" + b[0] + "' set to '" + b[1] + "' \n");
                           // TODO: Should check here that the custom port specified is a valid port number
                           if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "Should check here that the custom port specified is a valid port number!! \n");  }
                        } else {
                           throw new IllegalArgumentException(MALFORMED_PARAMETER_PREFIX + "'" + b[0] + "' " + MALFORMED_PARAMETER_ALREADY_DEFINED_SUFFIX + MALFORMED_PARAMETER_SUFFIX); 
                        }
                        break;
                       
                     case PARAMETER_NAME_FROM:
                        if (parameterFrom == null) {
                        
                           parameterFrom = "";
                           // Need to put our ISO date back together again as the parameter parsing splits will have sliced it up (>_<)
                           for (int bIndex = 1; bIndex < b.length; bIndex++) {
                              parameterFrom+= b[bIndex];
                           }                       
                           for (int aIndex = 2; aIndex < a.length; aIndex++) {
                              parameterFrom+= "-" + a[aIndex];
                           }

                           // TODO: Check it's a valid ISO date
                        
                           System.out.println("Parameter '" + b[0] + "' set to '" + parameterFrom + "' \n");

                        } else {
                           throw new IllegalArgumentException(MALFORMED_PARAMETER_PREFIX + "'" + b[0] + "' " + MALFORMED_PARAMETER_ALREADY_DEFINED_SUFFIX + MALFORMED_PARAMETER_SUFFIX); 
                        }
                        break;        
                       
                     case PARAMETER_NAME_UNTIL:
                        if (parameterUntil == null) {
 
                           parameterUntil = "";
                           // Need to put our ISO date back together again as the parameter parsing splits will have sliced it up (>_<)
                           for (int bIndex = 1; bIndex < b.length; bIndex++) {
                              parameterUntil+= b[bIndex];
                           }                       
                           for (int aIndex = 2; aIndex < a.length; aIndex++) {
                              parameterUntil+= "-" + a[aIndex];
                           }
 
                           // TODO: Check it's a valid ISO date

                           System.out.println("Parameter '" + b[0] + "' set to '" + parameterUntil + "' \n");
                        } else {
                           throw new IllegalArgumentException(MALFORMED_PARAMETER_PREFIX + "'" + b[0] + "' " + MALFORMED_PARAMETER_ALREADY_DEFINED_SUFFIX + MALFORMED_PARAMETER_SUFFIX); 
                        }
                        break;                                         
                  } // End of the switch statement
               }
            }
            else {  
               throw new IllegalArgumentException(MALFORMED_PARAMETER_PREFIX + MALFORMED_PARAMETER_NO_VALUE_PREFIX + "'" + b[0] + "'. " + MALFORMED_PARAMETER_SUFFIX); 
            }
        }
        else { // There was no hyphen prefix, so we must be defining the operation
           if (parameterOperation == null) {
              a[0] = a[0].toLowerCase();
              if (a[0].equals(OPERATION_NAME_SAVE) || a[0].equals(OPERATION_NAME_POST)) {
                    parameterOperation = a[0];
                       if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "Operation set to '" + a[0] + "' \n");  }
              } else {
                 throw new IllegalArgumentException(MALFORMED_PARAMETER_PREFIX + MALFORMED_PARAMETER_UNKNOWN_OPERATION_PREFIX + "(" + a[0] + ") " + MALFORMED_PARAMETER_SUFFIX); 
              }
           } else {
              throw new IllegalArgumentException(MALFORMED_PARAMETER_PREFIX + "Operation " + MALFORMED_PARAMETER_ALREADY_DEFINED_SUFFIX + MALFORMED_PARAMETER_SUFFIX); 
           }
        }

      }

      // Set defaults for uninitialized variables
      if (parameterPreserveHashtags == null) {  parameterPreserveHashtags = Boolean.valueOf(false);   }
      if (parameterPreserveBookmarks == null) {  parameterPreserveBookmarks = Boolean.valueOf(true);   }
      if (parameterPreservePins == null) {  parameterPreservePins = Boolean.valueOf(true);   }
      if (parameterBookmarkedOnly == null) {  parameterBookmarkedOnly = Boolean.valueOf(false);   }
      if (parameterExtraThrottleSeconds == null) {  parameterExtraThrottleSeconds = Integer.valueOf(0);   }     
      if (parameterSuppressPublic == null) {  parameterSuppressPublic = Boolean.valueOf(true);   }

      /**
      *   DO NOT set the custom port parameter to the default here, otherwise it will 
      *   prompt for credentials every time and never save them (thinking a custom port
      *    has been specified - this param should be left as null!)
      */

      if (parameterUsername == null) {  // Every operation needs a username (TODO: Change this to a check dependent on operation when conversion of Mastodon archive files is added)
         throw new IllegalArgumentException(MALFORMED_PARAMETER_PREFIX + MALFORMED_PARAMETER_NO_VALUE_PREFIX + "'" + PARAMETER_NAME_USERNAME + "'. " + MALFORMED_PARAMETER_SUFFIX); 
      }

      if (parameterInstance == null) {  // Every operation needs an instance (TODO: Change this to a check dependent on operation when conversion of Mastodon archive files is added)
         throw new IllegalArgumentException(MALFORMED_PARAMETER_PREFIX + MALFORMED_PARAMETER_NO_VALUE_PREFIX + "'" + PARAMETER_NAME_INSTANCE + "'. " + MALFORMED_PARAMETER_SUFFIX); 
      }

      if (parameterArchiveName == null) {  // Every operation needs an archive
         throw new IllegalArgumentException(MALFORMED_PARAMETER_PREFIX + MALFORMED_PARAMETER_NO_VALUE_PREFIX + "'" + PARAMETER_NAME_ARCHIVE_NAME + "'. " + MALFORMED_PARAMETER_SUFFIX); 
      }



      // TODO: Check parameters are valid for the specified operation, including that none that cannot be used are specified (will be more important when there are operations other than save and post)
   }


   /**
   *   Archives posts from the username and instance specified as parameters when the
   *   application was run, saving them locally within a directory named according to
   *   the archive name, also specified as a parameter, that is in turn within the 
   *   application's data directory.
   *   <br /><br />
   *   @throws Exception when something breaks (needs work - TODO)
   *   <br /><br />
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   private static void save() throws Exception 
   {

      System.out.println("Saving posts by " + parameterUsername + " on " + parameterInstance + " in archive name " + parameterArchiveName + " \n");

      MastodonClient authenticatedClient = Authenticator.getSingletonInstance().getClient(parameterInstance, parameterUsername, parameterCustomPort);
      if (authenticatedClient == null) {
         System.out.println(Mover.getErrorMessagePrefix() + "Exiting due to authentication failure \n");
         Mover.printEndTimestamp();
         System.exit(1);
      }

      String accountId = authenticatedClient.accounts().verifyCredentials().execute().getId();
      if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "id: " + accountId + " \n");  }

      PostArchive archive = PostArchive.getSingletonInstance(parameterArchiveName, false);    // Set loadOK parameter to "false" right now because incremental archives are not implemented yet (TODO)

      List<Status> results = new ArrayList();
      Range currentRange = new Range(null, null, STATUSES_PER_PAGE);
      int lastFetched = -1;

      System.out.print("Fetching pages ");

      /**
      *   Note: The last time this runs it will fetch zero new statuses, because when it
      *   fetches the last batch there will still be more than zero so it will run one 
      *   more time.
      */
      do {    
         Pageable<Status> timeline = authenticatedClient.accounts().getStatuses(accountId, false, false, false, currentRange).execute();
         List<Status> page = timeline.getPart();
         results.addAll(page);
         lastFetched = page.size();
         currentRange = timeline.nextRange(STATUSES_PER_PAGE);
         if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "Fetched " + results.size() + " statuses in total");  }
         if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "Fetched " + page.size() + " statuses this time \n");  }
         if (page.size() > 0) {
            Status last = (Status)page.get(page.size() - 1); 
            if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "Last status fetched id: " + last.getId() + " content: " + last.getContent() + " \n");  }
         }
         System.out.print(".");
         try {
            TimeUnit.SECONDS.sleep(API_THROTTLE_STATUS_PAGE_SECONDS + parameterExtraThrottleSeconds);  
         }
         catch (InterruptedException ie) {   }
      } while (lastFetched > 0);
      if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "Done - fetched " + results.size() + " statuses \n");  }


      // Estimate time required to archive posts as per parameters
      ListIterator q = results.listIterator(results.size());
      long secondsToCompletion = 0;   
      while (q.hasPrevious()) {
         Status s = (Status)q.previous();
         if ((!parameterBookmarkedOnly) || (parameterBookmarkedOnly && s.isBookmarked())) {
            secondsToCompletion = secondsToCompletion + API_THROTTLE_SECONDS + parameterExtraThrottleSeconds; // for the post text
            List<MediaAttachment> media = s.getMediaAttachments();
            if (media != null) {
               secondsToCompletion = secondsToCompletion + (media.size() * (API_THROTTLE_SECONDS + parameterExtraThrottleSeconds));
            }
         }
      }
      secondsToCompletion = Math.round( Math.ceil( secondsToCompletion * (1 + (DURATION_ESTIMATE_MARGIN_PERCENT / 100.0)) ) );
      Duration d = Duration.ofSeconds(secondsToCompletion);
      System.out.print("\n\nEstimated time to completion: ");
      printDuration(d);

      System.out.print("\nFetching post content: \n");
      // Main saving loop
      ListIterator i = results.listIterator(results.size());  // Get a listIterator with initial position set to the end (size()) - the first call to previous() returns position minus one
      while (i.hasPrevious()) {
         Status s = (Status)i.previous();

         if ((!parameterBookmarkedOnly) || (parameterBookmarkedOnly && s.isBookmarked())) {  // If we either don't care whether it's bookmarked, or we do *and* it is bookmarked

            String text = authenticatedClient.statuses().getStatusSource(s.getId()).execute().getText();

            Post newPost = archive.addPost(s.getCreatedAt(), PostArchive.getMastodonId(parameterInstance, s.getId()));

            System.out.print("Saving " + newPost.getArchiveId() + ": ");
            if (text.length() > INTERFACE_STATUS_TEXT_TRUNCATE_LENGTH) {  
               System.out.print(text.substring(0, INTERFACE_STATUS_TEXT_TRUNCATE_LENGTH).replaceAll("[\\n\\r\\t]+", "") + "...");   // Regex removes newlines, carriage returns and tabs
            } else {
               System.out.print(text.replaceAll("[\\n\\r\\t]+", ""));   // Regex removes newlines, carriage returns and tabs
            }
   
            synchronized (newPost) {
               newPost.pausePersistence();  // Don't save the post to file every time we change each attribute when we're setting so many at once. For safety, use within a synchronized block.
               newPost.setText(text);
               newPost.setVisibility(s.getVisibility().toLowerCase());
               newPost.setIsSensitive(s.isSensitive());
               newPost.setSpoilerText(s.getSpoilerText());
    
               /**
               *   Only store the "in reply to ID" if we're replying to ourselves in a 
               *   thread. It's meaningless to try to repost replies to other people's
               *   threads - it will cause strange notifications for them, and any 
               *   replies to our initial posts will become unthreaded.
               *   <br /><br />
               *   An account id is unique on each instance - so long as it equals our
               *   own on this instance, we are replying to ourselves.
               */
               String inReplyToAccountId = s.getInReplyToAccountId();
               if ((inReplyToAccountId != null) && (inReplyToAccountId.equals(accountId))) {   
   
                  if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "Replying to account id " + inReplyToAccountId + " \n");  }
   
                  /**
                  *   The reference we actually want to save as the inReplyToId, 
                  *   internally, is our own non-Mastodon reference (the archive id), 
                  *   based on the original post creation date. To do this, we need to
                  *   get the Mastodon Id of the post we're replying to, then check for
                  *   it in our own database, and then if we find it get our own internal
                  *   id for that post
                  */
                  String irti = PostArchive.getMastodonId(parameterInstance, s.getInReplyToId());
                  Post irtp = archive.getPostByMastodonId(irti);
   
                  if (irtp != null) {
                     newPost.setInReplyToArchiveId(irtp.getArchiveId());
                  }
   
               }  // end of the check that we are replying to ourselves
    
               Status reblogged = (Status)s.getReblog();
               if (reblogged != null) {
                  newPost.setReblogUrl(reblogged.getUrl());  // TODO: Will have to search for this URL using "resolve=true" to retoot it again
               }
   
               newPost.setLanguage(s.getLanguage());
               newPost.setIsFavourited(s.isFavourited());
               newPost.setIsBookmarked(s.isBookmarked());
               // if (s.isPinned() != null) {   newPost.setIsPinned(s.isPinned());   }   // up to v00.01.03 (pre-BigBone snapshot 17 bigbone-2.0.0-20230530.130705-17.jar)
               newPost.setIsPinned(s.isPinned());
   
               newPost.setFavouritesCount(s.getFavouritesCount());
               newPost.setReblogsCount(s.getReblogsCount());

               List<MediaAttachment> media = s.getMediaAttachments();
               for (MediaAttachment m : media) {
                  if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + " Processing media attachment with id " + m.getId() + " \n");  }
                  String mediaUrl = m.getUrl();
                  String mediaMastodonType = m.getType();
                  String mediaThumbnailUrl = null;
                  if (mediaMastodonType.toLowerCase().equals("video")) {
                     mediaThumbnailUrl = m.getPreviewUrl();               
                  }
                  String mediaAltText = m.getDescription();
                  float mediaFocalPointX = 0;
                  float mediaFocalPointY = 0;
                  Focus mediaFocalPoint = m.getMeta().getFocus();
                  if (mediaFocalPoint != null) {
                     mediaFocalPointX = mediaFocalPoint.getX();
                     mediaFocalPointY = mediaFocalPoint.getY();
                  }

                  /**
                  *   Technically we're not calling the API to download each of the 
                  *   images in this next call (and certainly not the higher-rate-limited
                  *   media API), but we are calling the instance's file storage. Let's 
                  *   be a good citizen and at least do a basic throttle together with
                  *   any additional set as a parameter.
                  */
                  try { TimeUnit.SECONDS.sleep(API_THROTTLE_SECONDS + parameterExtraThrottleSeconds);  }  catch (InterruptedException ie) {   }

                  newPost.addMedia(mediaUrl, mediaMastodonType, mediaThumbnailUrl, mediaAltText, mediaFocalPointX, mediaFocalPointY);  // Maybe should have a separate method for no thumbnails?
                  System.out.print(".");
               }  // end iterator over media
   
               newPost.resumePersistence();   // This is very important.

            }  // end synchronized (newPost)


            try { TimeUnit.SECONDS.sleep(API_THROTTLE_SECONDS + parameterExtraThrottleSeconds);  }  catch (InterruptedException ie) {   }
            System.out.println("");

         }  // end parameterBookmarkedOnly check

      }  // End while (iterating through posts)

      System.out.println("\nSuccessfully saved " + archive.getPostCount() + " posts." + " \n");   // TODO: Will need to add a counter for this when we add incremental archiving

   } // end saving method

   /**
   *   Reposts content stored in an archive specified as a parameter when the application
   *   was run to a username and instance, also specified as parameters.
   *   <br /><br />
   *   @throws Exception when something breaks (needs work)
   *   <br /><br />
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   private static void post() throws Exception 
   {

      System.out.println("Posting to " + parameterUsername + " on " + parameterInstance + " from archive name " + parameterArchiveName + " \n");

      MastodonClient authenticatedClient = Authenticator.getSingletonInstance().getClient(parameterInstance, parameterUsername, parameterCustomPort);
      if (authenticatedClient == null) {
         System.out.println(Mover.getErrorMessagePrefix() + "Exiting due to authentication failure \n");
         Mover.printEndTimestamp();
         System.exit(1);
      }

      String accountId = authenticatedClient.accounts().verifyCredentials().execute().getId();
      if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "id: " + accountId + " \n");  }


      // Get limits from target instance
      Instance targetInstance = authenticatedClient.instances().getInstance().execute();

      // int postLengthLimit = DEFAULT_POST_LENGTH_LIMIT_CHARACTERS;   // Adjust this when value is available from instance via BigBone
      int postLengthLimit = targetInstance.getConfiguration().getStatuses().getMaxCharacters();   
      if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "postLengthLimit: " + postLengthLimit + " \n");   }

      // int mediaAttachmentLimit = DEFAULT_MEDIA_ATTACHMENT_LIMIT_COUNT;   // Adjust this when value is available from instance via BigBone
      int mediaAttachmentLimit = targetInstance.getConfiguration().getStatuses().getMaxMediaAttachments();  
      if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "mediaAttachmentLimit: " + mediaAttachmentLimit + " \n");   }



      if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "parameterFrom: " + parameterFrom + " \n");   }
      if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "parameterUntil: " + parameterUntil + " \n");   }

      PostArchive archive = PostArchive.getSingletonInstance(parameterArchiveName, true);   


      // Estimate time required to repost posts as per parameters
      Iterator q = archive.getAllPosts().iterator();
      long secondsToCompletion = 0;   
      while (q.hasNext()) {
         Post p = (Post)q.next();
         if ((!parameterBookmarkedOnly) || (parameterBookmarkedOnly && p.isBookmarked())) {

            if (p.getText().length() > 0) { // TODO: Remove this when boosts are enabled

               // Date checking logic shamelessly copied and pasted from the main posting loop - TODO: consider whether to turn this into a convenience method
               boolean dateCheck = ((parameterFrom == null) && (parameterUntil == null));  // We don't have a from or an until parameter                                     
               dateCheck = (dateCheck || ( (parameterUntil == null) && (parameterFrom != null) && (p.isAfter(parameterFrom)) ) );    // We have only a "from" dateTime parameter, and the post is after it
               dateCheck = (dateCheck || ( (parameterFrom == null) && (parameterUntil != null) && (p.isBefore(parameterUntil)) ) );  // We have only an "until" dateTime parameter, and the post is after it      
               dateCheck = (dateCheck || ( (parameterFrom != null) && (parameterUntil != null) && (p.isAfter(parameterFrom)) && (p.isBefore(parameterUntil)) ) ); // We have a "from" and an "until" parameter, and the post is between them      

               if (dateCheck) {
   
                  // for the post itself
                  if ((!parameterSuppressPublic) && (p.getVisibility().toLowerCase().equals(Visibility.Public.toString().toLowerCase())) ) {
                     // post is public and we're not suppressing public posts
                     secondsToCompletion = secondsToCompletion + API_THROTTLE_NEW_PUBLIC_POST_SECONDS; 
                  } else {
                     // post isn't public or we are suppressing public posts
                     secondsToCompletion = secondsToCompletion + API_THROTTLE_NEW_NONPUBLIC_POST_SECONDS; 
                  }
                  secondsToCompletion = secondsToCompletion + parameterExtraThrottleSeconds; 

                  List<MediaFile> media = p.getMedia();
                  if (media != null) {
                     secondsToCompletion = secondsToCompletion + (media.size() * (API_THROTTLE_MEDIA_UPLOAD_SECONDS + parameterExtraThrottleSeconds)); // for uploading media
                  }
                  if (p.isBookmarked() && parameterPreserveBookmarks.booleanValue()) {
                     secondsToCompletion = secondsToCompletion + API_THROTTLE_SECONDS + parameterExtraThrottleSeconds; // for rebookmarking
                  }
                  if (p.isPinned() && parameterPreservePins.booleanValue()) {
                     secondsToCompletion = secondsToCompletion + API_THROTTLE_SECONDS + parameterExtraThrottleSeconds; // for repinning
                  }  

               }

            }  // length check
         }  // bookmarked / params
      }
      secondsToCompletion = Math.round( Math.ceil( secondsToCompletion * (1 + (DURATION_ESTIMATE_MARGIN_PERCENT / 100.0)) ) );
      Duration d = Duration.ofSeconds(secondsToCompletion);
      System.out.print("Estimated time to completion: ");
      printDuration(d);
      System.out.println(" ");


      int repostedCounter = 0;
      // Main reposting loop
      for (Post p : archive.getAllPosts()) {
     
         if (Mover.showDebug()) {   System.out.println(Mover.getDebugPrefix() + "p.isAfter(parameterFrom): " + ( (parameterFrom != null) && (p.isAfter(parameterFrom)) ));   }
         if (Mover.showDebug()) {   System.out.println(Mover.getDebugPrefix() + "p.isBefore(parameterUntil): " + ( (parameterUntil != null) && (p.isBefore(parameterUntil)) ));   }

         // We post if we either don't care whether it's bookmarked, or we do *and* it is bookmarked      
         boolean posting = ( (!parameterBookmarkedOnly) || (parameterBookmarkedOnly && p.isBookmarked()) );    
         
         // Emergency fix 5/24 for failure on reboosts (which are not implemented yet, but code instead just tries to post a status with zero-length text field)
         posting = posting && (p.getText().length() > 0);
                                        
         boolean dateCheck = ((parameterFrom == null) && (parameterUntil == null));  // We don't have a from or an until parameter                                     
         dateCheck = (dateCheck || ( (parameterUntil == null) && (parameterFrom != null) && (p.isAfter(parameterFrom)) ) );    // We have only a "from" dateTime parameter, and the post is after it
         dateCheck = (dateCheck || ( (parameterFrom == null) && (parameterUntil != null) && (p.isBefore(parameterUntil)) ) );  // We have only an "until" dateTime parameter, and the post is after it      
         dateCheck = (dateCheck || ( (parameterFrom != null) && (parameterUntil != null) && (p.isAfter(parameterFrom)) && (p.isBefore(parameterUntil)) ) ); // We have a "from" and an "until" parameter, and the post is between them      

         posting = posting && dateCheck;
         if (Mover.showDebug()) {   System.out.println(Mover.getDebugPrefix() + "Posting: " + posting);   }   

         if (posting) {
            
            System.out.print("Posting " + p.getArchiveId() + ": ");
            if (p.getText().length() > INTERFACE_STATUS_TEXT_TRUNCATE_LENGTH) {  
               System.out.print(p.getText().substring(0,INTERFACE_STATUS_TEXT_TRUNCATE_LENGTH).replaceAll("[\\n\\r\\t]+", "") + "...");   // Regex removes newlines, carriage returns and tabs
            } else {
               System.out.print(p.getText().replaceAll("[\\n\\r\\t]+", ""));   // Regex removes newlines, carriage returns and tabs --- TODO: Create a convenience "preview" method to trim the text to the maximum number of characters we want and remove these control characters - can reuse here and in the save() method
            }
   
            /**
            *   Visibility strangeness - because the API gives me a String but wants me to post a Visibility object
            *   see https://github.com/andregasser/bigbone/issues/192
            */             
            Visibility visibility = Visibility.Direct;
            String actualVisibility = p.getVisibility().toLowerCase();
            if (actualVisibility.equals(Visibility.Private.toString().toLowerCase())) { visibility = Visibility.Private; }
            else if (actualVisibility.equals(Visibility.Unlisted.toString().toLowerCase())) { visibility = Visibility.Unlisted; }
            else if (actualVisibility.equals(Visibility.Public.toString().toLowerCase())) { visibility = Visibility.Public; }

            // If parameter has been set to suppress public statuses (so they don't flood
            // the local and federated timelines), set any public statuses to unlisted
            if ((parameterSuppressPublic) && (visibility == Visibility.Public)) {
               visibility = Visibility.Unlisted;
            }

   
            // Post media and create list of IDs
            List<String> mediaIds = null;

            if (p.hasMedia()) {
   
               if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + " Uploading some media \n");  }
               mediaIds = new ArrayList();
               
               // TODO: Fix p.getMedia() so this copy is only read-only and doesn't affect the original data - for now, DO NOT change the objects in p.getMedia(), as it will change the stored data!
               for (MediaFile mf : p.getMedia()) {
   
                  Focus focalPoint = new Focus(mf.getFocalPointX(), mf.getFocalPointY());  // TODO: Should be fine even for videos because Mastodon just sets one at 0.0, 0.0 anyway - but perhaps we should check the media type and only set a focalPoint if it's not a video?
   
                  if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + " filepath " + mf.getFilepath() + " \n");  }
                  final ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

                  //   ********** START: 5/25 emergency image upload fix v0.01.02 ********** (TODO: Tidy this up -- log an issue to suggest a stream interface on the Bigbone library?)
                  //   final File media = new File(classLoader.getResource(mf.getFilepath()).getFile());   // Code used until this fix, which doesn't work when tool is run from a JAR file
                  String actualPath = mf.getFilepath().replace('/', File.separator.charAt(0));   // TODO: Check this is platform independent, also check why saving isn't affected (it tests ok), also figure out why we're using replaceAll in MediaFile (return filepath.replaceAll(Pattern.quote(File.separator), "/");) and if it's right there why it isn't right here... but not at 2am
                  final File media = new File(actualPath);
                  //   ********** END: 5/25 emergency image upload fix v0.01.02 **********

                  if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + " classLoader path " + media.getPath() + " \n");  }
         
                  if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "Alt text: " + mf.getAltText() + " \n");  }
   
                  final MediaAttachment uploadedFile = authenticatedClient.media().uploadMedia(media, mf.getMimeType(), mf.getAltText(), focalPoint).execute(); 
                  if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "File uploaded \n");  }

                  mediaIds.add(uploadedFile.getId());
                  System.out.print(".");
                  // Note - pause after each upload and not at end of posting because we need to throttle for each media upload (and there could be four per post!)
                  try { TimeUnit.SECONDS.sleep(API_THROTTLE_MEDIA_UPLOAD_SECONDS + parameterExtraThrottleSeconds);  }  catch (InterruptedException ie) {   }
               }
            }
   
            // Check for a replyToId for an earlier status in a self thread
            String irti = p.getInReplyToArchiveId();  // Gets the internal post Id (based on original post creation date) of our post to which this post replies
            String irtInstanceId = null;
            if (irti != null) {
               Post irtp = archive.getPostByArchiveId(irti);  // Gets the Post object this post replies to, but we need to find the Mastodon instance id for it on the instance we're reposting to
               if (irtp != null) {   // may happen if the archive is manually edited to remove folders for earlier posts - the reference remains on this post, but the post it points to won't be in the archive
                  irtInstanceId = irtp.getLatestMastodonId(parameterInstance); // Gets the most recent internal Mastodon instance id for this post on the instance we're reposting to          
                  if (irtInstanceId != null) {
                     irtInstanceId = PostArchive.getInstanceIdFromMastodonId(irtInstanceId);   // Gets the instance-specific id, without the part that specifies the instance address
                  }
               }
            }
   
            if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "Reply to - irti: " + irti + " \n");  }
            if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "Reply to - instanceId: " + irtInstanceId + " \n");  }
   
            // Strip any trailing spaces (mostly to prevent counting the length as over the instance post limit because of them even when it isn't really)
            String text = p.getText().stripTrailing();

            // Replace @ characters in text so long as they are preceded by a whitespace and are not the final character
            text = substituteCharacters(text, "@", "＠"); 

            // Replace # characters in text if parameter wasn't set to preserve them   
            if(!parameterPreserveHashtags.booleanValue()) {
               if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "parameterPreserveHashtags.booleanValue() " + parameterPreserveHashtags.booleanValue() + " \n");  }
   
               // Replace # characters in text so long as they are preceded by a whitespace and are not the final character
               text = substituteCharacters(text, "#", "⋕"); 
            }

            // As of v0.01.03, this section posts multiple statuses where text exceeds
            // the instance post character limit, or where attached media exceeds the
            // maximum number of media attachments for the instance

            ArrayList<String> textSegmented = new ArrayList();
            if (getPostedLength(text) > postLengthLimit) {   // Limit is based on the number of unicode "code points" (characters), not the number of unicode "code units" (blocks) that form them - see https://dzone.com/articles/java-string-length-confusion
               System.out.print("\nSplitting post into segments due to instance character limit (length: " + getPostedLength(text) + ", limit: " + postLengthLimit + ")");   // TODO: Make message part a constant
               textSegmented = segmentText(text, postLengthLimit);   // divide our text so each portion will not exceed the maximum character length for a post on this instance
            } else {
               if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "Not segmenting text (length: " + getPostedLength(text) + ") \n");  }
               textSegmented.add(text);
            }

            ArrayList<List> mediaIdsSegmented = new ArrayList();
            if (mediaIds != null) {
               if (mediaIds.size() > mediaAttachmentLimit) {
   
                  System.out.print("\nSplitting post into segments due to instance media attachment limit (attachments: " + mediaIds.size() + ", limit: " + mediaAttachmentLimit + ")");   // TODO: Make this message a constant
                  // Divide our media ids into lists that can be attached to each of our posts, with each list not exceeding the maximum number of media attachments for a status on the instance
                  Iterator mi = mediaIds.iterator();
                  ArrayList n = new ArrayList();
                  int j = 1;   
                  while(mi.hasNext()) {
                     n.add(mi.next()); 
                     j++;
                     if (j > mediaAttachmentLimit) {
                        mediaIdsSegmented.add((ArrayList)n.clone());   // (shallow) clone it because if we add n, then reassign n to a new object, we'll lose what we added (n is an object reference)!
                        n.clear();   // since we cloned, we may as well reuse rather than assigning to a newly instantiated ArrayList
                        j = 1;
                     }
                  }
                  mediaIdsSegmented.add((ArrayList)n.clone());   // for the last set, because we exited the while statement before hitting the maximum number that could be attached, so never added n to mediaIdsSegmented on the last iteration
   
               } else {
                  mediaIdsSegmented.add(mediaIds);
               }
               if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "mediaIdsSegmented: " + mediaIdsSegmented + " \n");  }
            }



            for (int k = 0; k < Math.max(textSegmented.size(), mediaIdsSegmented.size()); k++) {   // combined loop for all segments needed to post all text and all meda 

               String textThisTime = "";
               if (k < textSegmented.size()) {
                  textThisTime = textSegmented.get(k);
               }

               List mediaIdsThisTime = null;
               if (k < mediaIdsSegmented.size()) {
                  mediaIdsThisTime = mediaIdsSegmented.get(k);
                  if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "k: " + k + " mediaIdsThisTime: " + mediaIdsThisTime + " \n");  }

               }
               // TODO: Figure out how to handle Post data with multiple media files that
               // fall within the limit for number of attachments but that include a mix
               // (such as one video file and two image files) that is not accepted by an
               // instance - will currently throw an HTTP 422 error, I think

               // ***********************************************************************
               // Note: irtInstanceId is the Mastodon internal id on the instance we're 
               // posting to for a post we're replying to (defined earlier). First time 
               // this loops, it'll be the id of any previous status in a self-thread. 
               // From the second loop onward, it'll be the id for the status posted in 
               // the previous iteration (because multiple iterations means the status 
               // text in our archive was too long to post in a single status on this 
               // instance and had to be split, and we have to thread those multiple 
               // sections together). After posting we will reassign it within this loop
               // to the id of the status we just posted, to make this happen.

               Status z = (Status)authenticatedClient.statuses().postStatus(textThisTime, 
                                                                            visibility, 
                                                                            irtInstanceId, 
                                                                            mediaIdsThisTime, 
                                                                            p.isSensitive(), 
                                                                            p.getSpoilerText(), 
                                                                            p.getLanguage()
                                                                            ).execute();

               if (z == null) {    
  
                  if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + " Status was null after posting! \n");  }
                  throw new IllegalStateException ("Status z was null after posting");

               } else {

                  irtInstanceId = z.getId();   // as mentioned earlier, to thread our segments together

                  // Register the new Mastodon instance id for our post
                  p.addMastodonId(PostArchive.getMastodonId(parameterInstance, z.getId()));
                  System.out.print(".");

                  int sleepTime = parameterExtraThrottleSeconds;
                  if (visibility == Visibility.Public) {
                     sleepTime = sleepTime + API_THROTTLE_NEW_PUBLIC_POST_SECONDS;
                  } else {
                     sleepTime = sleepTime + API_THROTTLE_NEW_NONPUBLIC_POST_SECONDS; 
                  }
                  try { TimeUnit.SECONDS.sleep(sleepTime);  }  catch (InterruptedException ie) {   }
            
                  // Rebookmark if it hasn't been disabled - if we have multiple segments, rebookmark all of them
                  if (p.isBookmarked() && parameterPreserveBookmarks.booleanValue()) {

                     if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "rebookmarking " + z.getId() + " \n");  }               
                     authenticatedClient.statuses().bookmarkStatus(z.getId()).execute(); 
                     System.out.print(".");
                     try { TimeUnit.SECONDS.sleep(API_THROTTLE_SECONDS + parameterExtraThrottleSeconds);  }  catch (InterruptedException ie) {   }
                  }

                  // Repin if it hasn't been disabled - if we have multiple segments, rebookmark all of them            
                  if (p.isPinned() && parameterPreservePins.booleanValue()) {
      
                     if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "repinning " + z.getId() + " \n");  }                    
                     authenticatedClient.statuses().pinStatus(z.getId()).execute(); 
                     System.out.print(".");
                     try { TimeUnit.SECONDS.sleep(API_THROTTLE_SECONDS + parameterExtraThrottleSeconds);  }  catch (InterruptedException ie) {   }
                  }  

               }  // END of check that z is not null          
            
            }  // END of segment posting loop

            repostedCounter++;   // Leave this outside the segment posting loop as it's a counter for the posts in our archive that we've reposted, not the number of statuses we have posted to the instance
            System.out.println("");

         }  // end posting check (bookmarked only and date thresholds)

      }  // end for iterator through posts

      System.out.println("\nSuccessfully posted " + repostedCounter + " posts." + " \n");  

   } // end posting method

   private static void printDuration(Duration d) {

      boolean moreThanMinutes = false;

      if (d.toDaysPart() > 0) {
         System.out.print(d.toDaysPart() + " day");
         if (d.toDaysPart() != 1) {   System.out.print("s");   }
         System.out.print(" ");
         moreThanMinutes = true;
      }
      if (d.toHoursPart() > 0) {
         System.out.print(d.toHoursPart() + " hour");
         if (d.toHoursPart() != 1) {   System.out.print("s");   }
         System.out.print(" ");
         moreThanMinutes = true;
      }
      if (moreThanMinutes) {   System.out.print("and ");   }
      System.out.print(d.toMinutesPart() + " minute");
      if (d.toMinutesPart() != 1) {   System.out.print("s");   }
      System.out.println(" ");
   }

   private static String substituteCharacters(String text, String toSubstitute, String replacement) {

      boolean debugThis = false;  // Separate flag as output is too verbose to include in typical debug

      int j = text.indexOf(toSubstitute);
      while (j >= 0) {
        if (((j == 0) || Character.isWhitespace(text.codePointAt(j-1))) && (j < (text.length() - 1))) {   // Replace (if it's at the start of text or if the preceding character is whitespace) AND it's not the final character 
           text = text.substring(0,j) + replacement + text.substring(j + 1, text.length());
           if (debugThis) {  System.out.println(Mover.getDebugPrefix() + "Substituting " + replacement + " for " + toSubstitute + " at index " + j + " \n");  }                    
        }
        if (j < (text.length() - 1)) {   // Only search again if our current match isn't already the last character
           int newIndex = text.substring(j + 1, text.length()).indexOf(toSubstitute);   // We have to test from the next character, as it's possible we didn't replace the match at j (if it wasn't preceded by whitespace, for example)
           if (newIndex > 0) {
              j = j + 1 + newIndex;
           } else {
              j = -1;
           }
        } else {   // Our current match was the last character
           j = -1;
        }
        if (debugThis) {  System.out.println(Mover.getDebugPrefix() + "Next substitute at index " + j + " \n");  }                    
      }         

      return text;
   }

   private static ArrayList<String> segmentText(String originalText, int postLengthLimit) {

      String t = originalText.stripTrailing();   // Remove trailing spaces, so we don't end up posting a whole new status with just an ellipsis and some spaces
      ArrayList<String> segmentedText = new ArrayList();
      String ellipsis = "...";      
      int ellipsisLength = getPostedLength(ellipsis);  // Futureproofing in case we ever use some fancy character in here instead, or allow it to be set as a parameter

      if (postLengthLimit < ((2 * ellipsisLength) + 1)) {   throw new RuntimeException("Post length limit too low (segment limit below 1)");   }  // TODO: Tidy this - improve wording, make it a constant, perhaps use a different exception etc.


      // Note repeated from above, but limit is based on the number of unicode "code 
      // points" (characters), not the number of unicode "code units" (blocks) that form
      // them - see https://dzone.com/articles/java-string-length-confusion

      if (getPostedLength(t) <= postLengthLimit) {   // Let's check here, just in case  // TEST 1: Post is shorter than or equal to postLengthLimit (should return with one segment)

         segmentedText.add(t);
         return segmentedText;

      } else {   // TEST 2: Post is longer than postLengthLimit (should segment)

         if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "segmentText: Dividing text into segments \n");  }    

  
         while (getPostedLength(t) > 0) {
   
            if (getPostedLength(t) > (postLengthLimit - ellipsisLength)) {

               // Note the non-first, non-final segment will need two ellipses (one at the
               // beginning and one at the end)
               int segmentLimit = postLengthLimit - ellipsisLength;

               if (segmentedText.size() > 0) {   segmentLimit = segmentLimit - ellipsisLength;   }  // deduct the second ellipses length if we're not on the first segment (and we know we're not on the last segment because t is still too long for that)
   
               // Use a temporary string variable to hold the longest text that would fit 
               // in a segment, so we can find the last index of a space and end our
               // segment without splitting a word. 
               String s = t.substring(0, segmentLimit);
               int segmentEndIndex = -1;
               if (Character.isWhitespace(t.codePointAt(segmentLimit))) {   

                  // Catches case where the word boundary is exactly at the segment 
                  // limit, so searching for the last whitespace (else, below) would 
                  // remove the last word when it will actually fit. we know there will
                  // be characters at segmentLimit and beyond, as this is not the last
                  // segment

                  segmentEndIndex = segmentLimit;
               } else {
                  segmentEndIndex = getIndexOfLastWhitespace(s);  

                  if (segmentEndIndex > 0) {
                     s = t.substring(0, segmentEndIndex);   // TEST 3: Post is longer than postLengthLimit and segment contains spaces (should segment on space)
                  } else {   // We couldn't find any spaces at all, so just set the segmentEndIndex to the segmentLimit for the sake of the processing that follows
                     segmentEndIndex = segmentLimit;  // TEST 4: Post is longer than postLengthLimit and segment does not contain spaces, so leave truncated
                     // We already set s to t.substring(0, segmentLimit) when we first defined it, above, so there's no need to repeat that
                  }
               }

               if (containsUrl(s)) {

                  // Check the length accounting for unicode and urls, and adjust by adding
                  // or removing a word until we've fitted as much as we can in this segment
                  int sLength = getPostedLength(s);
   
                  while ((sLength < segmentLimit) && (segmentEndIndex < t.length()))  {   
   
                      if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "segmentText: Adding to segment as it is below limit (s: " + s + ") length: " + getPostedLength(s) + " (without ellipsis) \n");  }   
   
                      int tempIndex = getIndexOfFirstWhitespace(t.substring(segmentEndIndex + 1, t.length())); // we know t doesn't end on a space because we stripped trailing spaces, so we can safely use "segmentEndIndex + 1" here
                      if (tempIndex >= 0) {  // We don't know what is in between what we have and the next whitespace, so we can't do a boundary check mathematically here to make sure the addition won't push us over the limit
                         // TEST 5: Post is longer than postLengthLimit, segment contains url longer than 23 characters, and extra words are available (should add as many extra words as possible)
                         segmentEndIndex = segmentEndIndex + 1 + tempIndex;
                         s = t.substring(0, segmentEndIndex);  
                      } else { // We couldn't find any more whitespace, so add the rest of the string
                         // TEST 6: Post is longer than postLengthLimit, segment contains url longer than 23 characters, and extra words are NOT available (should add the rest of the string)
                         s = t.substring(0, t.length());  
                         segmentEndIndex = t.length();
                      }
      
                      sLength = getPostedLength(s);    

                  }   // END: adding while
   
                  // At this point we may have ended up over the segment limit
   
                  while ((sLength > segmentLimit) && (s.length() > 1)) {  // Boundary condition (s.length() > 1) should never be needed here - for s.length() to be 1 (or below), you'd need a space on the second (or first) character while also having a valid url before that (otherwise that single character would form its own segment, without a url, and would never enter this block) TEST 7: A single character followed by a space is at the start of a candidate segment, followed by a block with no spaces (to confirm understanding)
                     // TEST 8: Post is longer than postLengthLimit, segment is longer than segment limit due to preceding processing (should be trimmed) 
                     // TEST 9: Post is longer than postLengthLimit, segment contains url shorter than 23 characters (should be trimmed) 
      
                      if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "segmentText: Trimming segment as it is over limit (s: " + s + ") length: " + getPostedLength(s) + " (without ellipsis) \n");  }   
   
                      int tempIndex = getIndexOfLastWhitespace(s); // we already ended s on a word (excluding the space that followed), so this should find the next space moving backward through the text
                      if (tempIndex >= 0) {
                         // TEST 10: Post is longer than postLengthLimit, segment needs to be trimmed and can be trimmed on word boundary (is trimmed on word boundary)
                         segmentEndIndex = tempIndex;
                         s = t.substring(0, segmentEndIndex);  
                      } else {   // we couldn't find any whitespace within s (excluding the final character, which we know isn't whitespace) but we know we're over the limit, so just truncate
                         // TEST 11: Post is longer than postLengthLimit, segment needs to be trimmed and cannot be trimmed on word boundary (should be truncated at the longest value for which the sLength (postedLength) is below the segment limit)
   
                         if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "segmentText: Failed to trim segment to end on word boundary - truncating iteratively \n");  }  
                         segmentEndIndex = s.length(); 
                         while ((sLength > segmentLimit) & (segmentEndIndex > 0)) {
                            segmentEndIndex--;
                            s = t.substring(0, segmentEndIndex);  
                            sLength = getPostedLength(s);  
                         }
                         if (segmentEndIndex == 0) {   throw new RuntimeException("segmentText: Failed to truncate iteratively while segmenting post");   }  // TODO: Tidy this - improve wording, make it a constant, perhaps use a different exception etc.
                         // We should now exit the trimming while loop below the segment limit
                      }    
                      sLength = getPostedLength(s);  

                  // System.out.println("s.length(): " + s.length() + " (for TEST 7)");

                  }   // END: trimming while

               }   // END: if (containsUrl(s))

               s = s + ellipsis;   
               if (segmentedText.size() > 0) {   s = ellipsis + s;   }   // add an ellipsis at the start if we're not on the first segment
   
               segmentedText.add(s);
               if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "segmentText: Segment " + (segmentedText.size() - 1) + ": " + segmentedText.get(segmentedText.size() - 1) + " length: " + getPostedLength(segmentedText.get(segmentedText.size() - 1)) + " \n");  }  
               t = t.substring(segmentEndIndex, t.length()).stripLeading();   // have to stripLeading here, as if the segmentEndIndex is on a space we need to remove the space but if we truncated a block of text we couldn't split arbitrarily removing one character will remove an actual character from that block, not a space
   
            } else {   // Last segment - we don't need to adjust this if it contains urls as we already identified the last segment using getPostedLength() in our if statement, above
               segmentedText.add(ellipsis + t);
               if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "segmentText: Segment " + (segmentedText.size() - 1) + " (last): " + segmentedText.get(segmentedText.size() - 1) + " length: " + getPostedLength(segmentedText.get(segmentedText.size() - 1)) + " \n");  }    
               t = "";
            }
   
         }
         
         if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "segmentText: Original text was " + originalText + " \n");  }                    
         if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "segmentText: Returning " + segmentedText + " \n");  }       
         // TEST 12: Returned segments should be suffixed with ellipsis (segment 1), prefixed and suffixed with ellipsis (segments 2 - last-1) and prefixed with ellipsis (last segment)
         return segmentedText;   
      }  
   }

   private static void testSegmentText() {

   String testText = "";
   ArrayList<String> result = null;

   System.out.println("***************************************************************************************************");
   System.out.println("TEST 1: Post is shorter than or equal to postLengthLimit (should return with one segment)");

   testText = "Weapons cleave it not, fire consumeth it not; the waters do not drench it, nor doth the wind waste it. It is incapable of being cut, burnt, drenched, or dried up. It is unchangeable, all-pervading, stable, firm, and eternal. It is said to be imperceivable, inconceivable and unchangeable.";
   result = segmentText(testText, DEFAULT_POST_LENGTH_LIMIT_CHARACTERS);
   System.out.println(result);
   
   System.out.println("***************************************************************************************************");
   System.out.println("TEST 2: Post is longer than postLengthLimit (should segment)");
   System.out.println("AND");
   System.out.println("TEST 3: Post is longer than postLengthLimit and segment contains spaces (should segment on space)");

   testText = "Know that [the soul] to be immortal by which all this [universe] is pervaded. No one can compass the destruction of that which is imperishable. It hath been said that those bodies of the Embodied (soul) which is eternal, indestructible and infinite, have an end. Do thou, therefore, fight, O Bharata. He who thinks it (the soul) to be the slayer and he who thinks it to be the slain, both of them know nothing; for it neither slays nor is slain. It is never born, nor doth it ever die; nor, having existed, will it exist no more. Unborn, unchangeable, eternal, and ancient, it is not slain upon the body being perished. That man who knoweth it to be indestructible, unchangeable, without decay, how and whom can he slay or cause to be slain? As a man, casting off robes that are worn out, putteth on others that are new, so the Embodied (soul), casting off bodies that are worn out, entereth other bodies that are new. Weapons cleave it not, fire consumeth it not; the waters do not drench it, nor doth the wind waste it. It is incapable of being cut, burnt, drenched, or dried up. It is unchangeable, all-pervading, stable, firm, and eternal. It is said to be imperceivable, inconceivable and unchangeable.";
   result = segmentText(testText, DEFAULT_POST_LENGTH_LIMIT_CHARACTERS);

   System.out.println("***************************************************************************************************");
   System.out.println("TEST 4: Post is longer than postLengthLimit and segment does not contain spaces (should truncate)");

   testText = "Knowthat[thesoul]tobeimmortalbywhichallthis[universe]ispervaded.Noonecancompassthedestructionofthatwhichisimperishable.IthathbeensaidthatthosebodiesoftheEmbodied(soul)whichiseternal,indestructibleandinfinite,haveanend.Dothou,therefore,fight,OBharata.Hewhothinksit(thesoul)tobetheslayerandhewhothinksittobetheslain,bothofthemknownothing;foritneitherslaysnorisslain.Itisneverborn,nordothiteverdie;nor,havingexisted,willitexistnomore.Unborn,unchangeable,eternal,andancient,itisnotslainuponthebodybeingperished.Thatmanwhoknowethittobeindestructible,unchangeable,withoutdecay,howandwhomcanheslayorcausetobeslain?Asaman,castingoffrobesthatarewornout,puttethonothersthatarenew,sotheEmbodied(soul),castingoffbodiesthatarewornout,enterethotherbodiesthatarenew.Weaponscleaveitnot,fireconsumethitnot;thewatersdonotdrenchit,nordoththewindwasteit.Itisincapableofbeingcut,burnt,drenched,ordriedup.Itisunchangeable,all-pervading,stable,firm,andeternal.Itissaidtobeimperceivable,inconceivableandunchangeable.";
   result = segmentText(testText, DEFAULT_POST_LENGTH_LIMIT_CHARACTERS);

   System.out.println("***************************************************************************************************");
   System.out.println("TEST 5: Post is longer than postLengthLimit, segment contains url longer than 23 characters, and extra words are available (should add as many extra words as possible)");   // See segment zero
   System.out.println("AND");
   System.out.println("TEST 6: Post is longer than postLengthLimit, segment contains url longer than 23 characters, and extra words are NOT available (should add the rest of the string)");   // Note - it does for segment one, and then it removes it again because there's a space after the url

   testText = "Know that [the soul] to be immortal by which all this [universe] is pervaded. https://www.sacred-texts.com/hin/m06/m06026.htm No one can compass the destruction of that which is imperishable. https://www.sacred-texts.com/hin/m06/m06026.htm It hath been said that those bodies of the Embodied (soul) which is eternal, indestructible and infinite, have an end. Do thou, therefore, fight, O Bharata. He who thinks it (the soul) to be the slayer and he who thinks it to be the slain, both of them know nothing; for it neither slays nor is slain. It is never born, nor doth it ever die; nor, having existed, will it exist no more. Unborn,unchangeable,eternal,andancient,itisnotslainuponthebodybeingperished. https://www.sacred-texts.com/hin/m06/m06026.htm Thatmanwhoknowethittobeindestructible,unchangeable,withoutdecay,howandwhomcanheslayorcausetobeslain? https://www.sacred-texts.com/hin/m06/m06026.htm Asaman,castingoffrobesthatarewornout,puttethonothersthatarenew,sotheEmbodied(soul),castingoffbodiesthatarewornout,enterethotherbodiesthatarenew.Weaponscleaveitnot,fireconsumethitnot;thewatersdonotdrenchit,nordoththewindwasteit.Itisincapableofbeingcut,burnt,drenched,ordriedup.Itisunchangeable,all-pervading,stable,firm,andeternal.Itissaidtobeimperceivable,inconceivableandunchangeable.";
   result = segmentText(testText, DEFAULT_POST_LENGTH_LIMIT_CHARACTERS);

   System.out.println("***************************************************************************************************");
   System.out.println("TEST 7: A single character followed by a space is at the start of a candidate segment, followed by a block with no spaces (to confirm understanding)");

   testText = "K nowthat[thesoul]tobeimmortalbywhichallthis[universe]ispervaded.https://mas.to/Noonecancompassthedestructionofthatwhichisimperishable.IthathbeensaidthatthosebodiesoftheEmbodied(soul)whichiseternal,indestructibleandinfinite,haveanend.Dothou,therefore,fight,OBharata.Hewhothinksit(thesoul)tobetheslayerandhewhothinksittobetheslain,bothofthemknownothing;foritneitherslaysnorisslain.Itisneverborn,nordothiteverdie;nor,havingexisted,willitexistnomore.Unborn,unchangeable,eternal,andancient,itisnotslainuponthebodybeingperished.Thatmanwhoknowethittobeindestructible,unchangeable,withoutdecay ,howandwhomcanheslayorcausetobeslain?http://mas.to/Asaman,castingoffrobesthatarewornout,puttethonothersthatarenew,sotheEmbodied(soul),castingoffbodiesthatarewornout,enterethotherbodiesthatarenew.Weaponscleaveitnot,fireconsumethitnot;thewatersdonotdrenchit,nordoththewindwasteit.Itisincapableofbeingcut,burnt,drenched,ordriedup.Itisunchangeable,all-pervading,stable,firm,andeternal.Itissaidtobeimperceivable,inconceivableandunchangeable.Therefore,knowingittobesuch,itbehoveththeenottomourn(forit).Thenagainevenifthouregardestitasconstantlybornandconstantlydead,itbehoveththeenotyet,Omighty-armedone,tomourn(forit)thus.For,ofonethatisborn,deathiscertain;andofonethatisdead,birthiscertain.Therefore.itbehoveththeenottomourninamatterthatisunavoidable.Allbeings(beforebirth)wereunmanifest.Onlyduringaninterval(betweenbirthanddeath),OBharata,aretheymanifest;andthenagain,whendeathcomes,theybecome(oncemore)unmanifest.Whatgriefthenisthereinthis?Onelooksuponitasamarvel;anotherspeaksofitasamarvel.Yetevenafterhavingheardofit,nooneapprehendsittruly.";
   result = segmentText(testText, DEFAULT_POST_LENGTH_LIMIT_CHARACTERS);

   System.out.println("***************************************************************************************************");
   System.out.println("TEST 8: Post is longer than postLengthLimit, segment is longer than segment limit due to preceding processing (should be trimmed) ");
   System.out.println("AND");
   System.out.println("TEST 10: Post is longer than postLengthLimit, segment needs to be trimmed and can be trimmed on word boundary (is trimmed on word boundary)");

   testText = "Know that [the soul] to be immortal by which all this [universe] is pervaded. https://www.sacred-texts.com/hin/m06/m06026.htm No one can compass the destruction of that which is imperishable. https://www.sacred-texts.com/hin/m06/m06026.htm It hath been said that those bodies of the Embodied (soul) which is eternal, indestructible and infinite, have an end. Do thou, therefore, fight, O Bharata. He who thinks it (the soul) to be the slayer and he who thinks it to be the slain, both of them know nothing; for it neither slays nor is slain. It is never born, nor doth it ever die; nor, having existed, will it exist no more. Unborn, unchangeable, eternal, and ancient, it is not slain upon the body being perished. https://www.sacred-texts.com/hin/m06/m06026.htm That man who knoweth it to be indestructible, unchangeable, without decay, how and whom can he slay or cause to be slain? https://www.sacred-texts.com/hin/m06/m06026.htm As a man, casting off robes that are worn out, putteth on others that are new, so the Embodied (soul), casting off bodies that are worn out, entereth other bodies that are new. Weapons cleave it not, fire consumeth it not; the waters do not drench it, nor doth the wind waste it. It is incapable of being cut, burnt, drenched, or dried up. It is unchangeable, all-pervading, stable, firm, and eternal. https://www.sacred-texts.com/hin/m06/m06026.htm It is said to be imperceivable, inconceivable and unchangeable. https://www.sacred-texts.com/hin/m06/m06026.htm";
   result = segmentText(testText, DEFAULT_POST_LENGTH_LIMIT_CHARACTERS);

   System.out.println("***************************************************************************************************");
   System.out.println("TEST 9: Post is longer than postLengthLimit, segment contains url shorter than 23 characters (should be trimmed) ");
   System.out.println("AND");
   System.out.println("TEST 10: Post is longer than postLengthLimit, segment needs to be trimmed and can be trimmed on word boundary (is trimmed on word boundary)");

   testText = "Know that [the soul] to be immortal by which all this [universe] is pervaded. https://mas.to/ No one can compass the destruction of that which is imperishable. https://mas.to/ It hath been said that those bodies of the Embodied (soul) which is eternal, indestructible and infinite, have an end. Do thou, therefore, fight, O Bharata. He who thinks it (the soul) to be the slayer and he who thinks it to be the slain, both of them know nothing; for it neither slays nor is slain. It is never born, nor doth it ever die; nor, having existed, will it exist no more. Unborn, unchangeable, eternal, and ancient, it is not slain upon the body being perished. That man who knoweth it to be indestructible, unchangeable, without decay, how and whom can he slay or cause to be slain? As a man, casting off robes that are worn out, putteth on others that are new, so the Embodied (soul), casting off bodies that are worn out, entereth other bodies that are new. Weapons cleave it not, fire consumeth it not; the waters do not drench it, nor doth the wind waste it. It is incapable of being cut, burnt, drenched, or dried up. It is unchangeable, all-pervading, stable, firm, and eternal. It is said to be imperceivable, inconceivable and unchangeable.";
   result = segmentText(testText, DEFAULT_POST_LENGTH_LIMIT_CHARACTERS);

   System.out.println("***************************************************************************************************");
   System.out.println("TEST 11: Post is longer than postLengthLimit, segment needs to be trimmed and cannot be trimmed on word boundary (should be truncated at the longest value for which the sLength (postedLength) is below the segment limit)");

   testText = "Knowthat[thesoul]tobeimmortalbywhichallthis[universe]ispervaded.https://mas.to/Noonecancompassthedestructionofthatwhichisimperishable.https://mas.to/IthathbeensaidthatthosebodiesoftheEmbodied(soul)whichiseternal,indestructibleandinfinite,haveanend.Dothou,therefore,fight,OBharata.Hewhothinksit(thesoul)tobetheslayerandhewhothinksittobetheslain,bothofthemknownothing;foritneitherslaysnorisslain.Itisneverborn,nordothiteverdie;nor,havingexisted,willitexistnomore.Unborn,unchangeable,eternal,andancient,itisnotslainuponthebodybeingperished.Thatmanwhoknowethittobeindestructible,unchangeable,withoutdecay,howandwhomcanheslayorcausetobeslain?Asaman,castingoffrobesthatarewornout,puttethonothersthatarenew,sotheEmbodied(soul),castingoffbodiesthatarewornout,enterethotherbodiesthatarenew.Weaponscleaveitnot,fireconsumethitnot;thewatersdonotdrenchit,nordoththewindwasteit.Itisincapableofbeingcut,burnt,drenched,ordriedup.Itisunchangeable,all-pervading,stable,firm,andeternal.Itissaidtobeimperceivable,inconceivableandunchangeable.";
   result = segmentText(testText, DEFAULT_POST_LENGTH_LIMIT_CHARACTERS);

   System.out.println("***************************************************************************************************");
   System.out.println("TEST 12: Returned segments should be suffixed with ellipsis (segment 1), prefixed and suffixed with ellipsis (segments 2 - last-1) and prefixed with ellipsis (last segment) - SEE PREVIOUS");

   }

   // TODO: (Maybe) Adjust this to account for Mastodon's shortening of @ tags as well, 
   // in case not stripping @ tags is ever added as a feature (if it is, this code as it
   // stands won't calculate the posted length correctly as it doesn't account for 
   // Mastodon's shortening of @-tagged names to only the handle before the @ domain)
   // (issue encountered by user RecoveredExpert in early June 2023)
   private static int getPostedLength(String text) {  // Calculates the number of characters that will be used on Mastodon to post this String, based on code points rather than chars/blocks, and urls always being 23 characters

      boolean debugThis = false;  // Separate flag as output is too verbose to include in typical debug

      if (debugThis) {  System.out.println(Mover.getDebugPrefix() + "Checking posted length for: " + text + " \n");  }  

      String s = text.stripTrailing();

      // We need to remove any links starting with http:// or https:// and count the
      // number of times we do this, then add the character count used for urls back
      // onto the length of what is left the same number of times. Could also replace
      // the links with a block of characters that matches that count and then just 
      // measure the number of code points, if there are issues with doing it this way
      int linkCount = 0;
      int startIndex = s.indexOf("https://");
      if (startIndex < 0) {   startIndex = s.indexOf("http://");   }

      while (startIndex >= 0) {

         int urlLength = getValidUrlLength(s.substring(startIndex, s.length()));  

         if (urlLength < 0) {   // Only perform our counting adjustment if we have a valid url (it's possible to start with https:// or http:// and not be a valid uri - this can be tested in the Mastodon web interface)
            startIndex = startIndex + (s.substring(startIndex, s.length()).indexOf("//")); // just skip to the double forward slash without increasing our cound, so that the next time we look for the start of a url, with https:// or http://, we'll skip this one 
            if (debugThis) {  System.out.println(Mover.getDebugPrefix() + "Invalid uri starting with http:// or https:// found - skipping \n");  }  
         } else {
            linkCount++;
            if (debugThis) {  System.out.println(Mover.getDebugPrefix() + "Valid url " + s.substring(startIndex, (startIndex + urlLength)) +  " found from " + startIndex + ", length " + urlLength + ", link count: " + linkCount + " \n");  }  
            s = s.substring(0,startIndex) + s.substring((startIndex + urlLength), s.length());   // trim out the link
         }

         // Search the rest of the string and find the next startIndex, or exit with -1 if there are no more
         int n = s.substring(startIndex, s.length()).indexOf("https://");
         if (n < 0) {   startIndex = s.indexOf("http://");   }

         if (n < 0) {    // There are no more http urls in this string, so exit the while loop by stetting startIndex to -1
            startIndex = n;   
         } else {
            startIndex = startIndex + n;   // We searched the rest of the string to find n, but our index actually needs to start from 0
         }
      }

      // We should now have a string stripped of all valid http urls, and a count of the number of valid http urls

      if (debugThis) {  System.out.println(Mover.getDebugPrefix() + "Post stripped of urls: " + s + " \n");  }  
      int postedLength = s.codePointCount(0, s.length()) + (linkCount * DEFAULT_URL_CHARACTER_COUNT);
      if (debugThis) {  System.out.println(Mover.getDebugPrefix() + "Posted length: " + postedLength + " \n");  }  
      return postedLength;
   }

   private static int getValidUrlLength(String text) {   

      boolean debugThis = false;  // Separate flag as output is too verbose to include in typical debug

      String s = text.stripTrailing();

      String regex = "^(https?:\\/\\/)[-a-zA-Z0-9@:%._\\+~#=]{1,256}\\.[a-z]{2,6}\\b([-a-zA-Z0-9@:%_\\+.~#?&\\/=]*)$";
      // (sourced and adjusted from https://www.makeuseof.com/regular-expressions-validate-url/) explained:
      // ^ is the start of the matching string
      // (https?:\/\/) matches http:// or https://
      // [-a-zA-Z0-9@:%._\+~#=]{1,256}\. matches characters listed in the square brackes between one and 256 times - a combined domain and subdomain must be between one and 256 characters (Mastodon allows one-character domains)
      // [a-z]{2,6} matches a top level domain consisting only of characters from a to z between two and six times - a top-level domain
      // \b matches a slash or any other character not in [a-zA-Z0-9_] (not matching regex \w) after the tld, to make sure subsequent characters don't extend the tld further (it will let them if \b is omitted)
      // ([-a-zA-Z0-9@:%_\+.~#?&\/=]*) matches any of the characters between the parentheses zero or more times (the asterisk) 

      boolean valid = false;   

      // Multiple substrings of the whole url, starting from the beginning, will be
      // recognised as valid urls, so instead let's start from the end and then stop
      // as soon as we find a url that is valid (ie doesn't have a load of characters
      // that are not part of the url on the end)

      int index = s.length() + 1;   // First iteration will start by subtracting one, so will start from the full text of s
      while ((!valid) && (index > 0)) {   // Stop before the index reaches zero as iterations start by subtracting one which must not take them below zero 
         index--;
         if (s.substring(0, index).matches(regex)) {   
            valid = true;   
            if (debugThis) {  System.out.println(Mover.getDebugPrefix() + "getValidUrlLength candidate url " + s.substring(0, index) + " is valid \n");  }
         } else {
            if (debugThis) {  System.out.println(Mover.getDebugPrefix() + "getValidUrlLength candidate url " + s.substring(0, index) + " is not yet valid \n");  } 
         }
      } // end of while loop

      if (debugThis) { System.out.println(Mover.getDebugPrefix() + "getValidUrlLength returning length for url " + s.substring(0,index) + " \n");  }

      return index;
   }

   private static boolean containsUrl(String text) {

      String regex = "(https?:\\/\\/)[-a-zA-Z0-9@:%._\\+~#=]{1,256}\\.[a-z]{2,6}\\b([-a-zA-Z0-9@:%_\\+.~#?&\\/=]*)";   

      // This regex has to be different to that used in getValidUrlLength(String text), 
      // which insists on being the full string whereas this should match any substring 
      // (notice the missing ^ and $ at each end)

      boolean r = Pattern.compile(regex).matcher(text).find();
      if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "Mover#containsUrl: result: " + r + " \n");  }  
      return r;
   }

   private static int getIndexOfFirstWhitespace(String text) {
      
      boolean found = false;
      int j = -1;

      while ((!found) && (j < (text.length() - 2))) {
         j++;
         found = Character.isWhitespace(text.codePointAt(j));

      }
      if (found) {
         return j;   
      } else {
         return -1;
      }
   }

   private static int getIndexOfLastWhitespace(String text) {

      boolean found = false;
      int j = text.length();

      while ((!found) && (j > 0)) {
         j--;
         found = Character.isWhitespace(text.codePointAt(j));
      }
      if (found) {
         return j;   
      } else {
         return -1;
      }
   }


   /**
   *   Provides the location of the application's data directory, currently as specified
   *   by the {@code DATA_DIRECTORY} constant.
   *   <br /><br />
   *   @return the folder name within the working directory that is used to store all the
   *           application's data
   *   <br /><br />
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected static String getDataDirectory() {
      return DATA_DIRECTORY;
   }

   /**
   *   Confirms whether the application's data directory exists at the location specified
   *   by the {@code DATA_DIRECTORY} constant, and tries to create it if it does not.
   *   <br /><br />
   *   @return {@code true} if the directory exists or was successfully created, or 
   *   {@code false} otherwise
   *   <br /><br />
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected static boolean checkDataDirectoryExists() {

      if (new File(DATA_DIRECTORY).exists()) {
         return true;
      } else {
         return new File(DATA_DIRECTORY).mkdirs();    // returns true if the directory was created with any parent directories, false otherwise
      }
   }


   /**
   *   Indicates whether debug messages should be printed to the console, according to
   *   whether that option was specified as a parameter when the application was run.
   *   <br /><br />
   *   @return {@code true} if debug messages should be printed to the console, or
   *   {@code false} otherwise
   *   <br /><br />
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected static boolean showDebug() {
      return parameterShowDebug;
   }

   /**
   *   Provides the prefix used when debug messages are printed to the console, as 
   *   specified by the {@code DEBUG_PREFIX} constant.
   *   <br /><br />
   *   @return the prefix used when debug messages are printed to the console
   *   <br /><br />
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected static String getDebugPrefix() {
      return DEBUG_PREFIX;
   }

   /**
   *   Provides the prefix used when error messages are printed to the console, as 
   *   specified by the {@code ERROR_MESSAGE_PREFIX} constant.
   *   <br /><br />
   *   Do not use this to prefix the messages attached to thrown exceptions, or the
   *   prefix will be displayed twice.
   *   <br /><br />
   *   @return the prefix used when error messages are printed to the console
   *   <br /><br />
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected static String getErrorMessagePrefix() {
      return ERROR_MESSAGE_PREFIX;
   }



}