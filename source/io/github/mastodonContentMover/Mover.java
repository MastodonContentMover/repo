package io.github.mastodonContentMover;

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;
import java.util.Locale;
import java.time.format.DateTimeFormatter;
import java.time.ZonedDateTime;
import java.io.File;

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

   // API settings
   private static final int STATUSES_PER_PAGE = 40;   // Current maximum
   private static final int API_THROTTLE_SECONDS = 5;  // Actual limit is 1
   private static final int API_THROTTLE_STATUS_PAGE_SECONDS = 5;  // Actual limit is 1
   private static final int API_THROTTLE_NEW_POST_SECONDS = 5;  // Actual limit is 1
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

      System.out.print("\nFetching post content: \n"); // Have to send carriage return first, after the dots

     
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
               if (s.isPinned() != null) {   newPost.setIsPinned(s.isPinned());   }
   
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
      if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "parameterFrom: " + parameterFrom + " \n");   }
      if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "parameterUntil: " + parameterUntil + " \n");   }

      PostArchive archive = PostArchive.getSingletonInstance(parameterArchiveName, true);   
      int repostedCounter = 0;

      for (Post p : archive.getAllPosts()) {
     
         if (Mover.showDebug()) {   System.out.println(Mover.getDebugPrefix() + "p.isAfter(parameterFrom): " + ( (parameterFrom != null) && (p.isAfter(parameterFrom)) ));   }
         if (Mover.showDebug()) {   System.out.println(Mover.getDebugPrefix() + "p.isBefore(parameterUntil): " + ( (parameterUntil != null) && (p.isBefore(parameterUntil)) ));   }

         // We post if we either don't care whether it's bookmarked, or we do *and* it is bookmarked      
         boolean posting = ( (!parameterBookmarkedOnly) || (parameterBookmarkedOnly && p.isBookmarked()) );    
                                        
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
                  final File media = new File(classLoader.getResource(mf.getFilepath()).getFile());
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
   
            // Check for a replyToId
            String irti = p.getInReplyToArchiveId();  // Gets the internal post Id (based on original post creation date) of our post to which this post replies
            String instanceId = null;
            if (irti != null) {
               Post irtp = archive.getPostByArchiveId(irti);  // Gets the Post object this post replies to, but we need to find the Mastodon instance id for it on the instance we're reposting to
               instanceId = irtp.getLatestMastodonId(parameterInstance); // Gets the most recent internal Mastodon instance id for this post on the instance we're reposting to          
               if (instanceId != null) {
                  instanceId = PostArchive.getInstanceIdFromMastodonId(instanceId);   // Gets the instance-specific id, without the part that specifies the instance address
               }
            }
   
            if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "Reply to - irti: " + irti + " \n");  }
            if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "Reply to - instanceId: " + instanceId + " \n");  }
   
   
            // Replace @ characters in text
            String text = p.getText().replaceAll("@", "＠");

            // Replace # characters in text if parameter wasn't set to preserve them   
            if(!parameterPreserveHashtags.booleanValue()) {
               if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "parameterPreserveHashtags.booleanValue() " + parameterPreserveHashtags.booleanValue() + " \n");  }
   
               // Replace # characters in text
               text = text.replaceAll("#", "⋕");
            }
   
            Status z = authenticatedClient.statuses().postStatus(text, 
                                                                 visibility,
                                                                 instanceId,   // replying to
                                                                 mediaIds, 
                                                                 p.isSensitive(), 
                                                                 p.getSpoilerText(), 
                                                                 p.getLanguage()
                                                                 ).execute();
   
            // Register the new Mastodon instance id for our post
            p.addMastodonId(PostArchive.getMastodonId(parameterInstance, z.getId()));
            System.out.print(".");
            try { TimeUnit.SECONDS.sleep(API_THROTTLE_NEW_POST_SECONDS + parameterExtraThrottleSeconds);  }  catch (InterruptedException ie) {   }
            
            // Rebookmark if it hasn't been disabled
            if (p.isBookmarked() && parameterPreserveBookmarks.booleanValue()) {

               if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "rebookmarking " + z.getId() + " \n");  }               
               authenticatedClient.statuses().bookmarkStatus(z.getId()).execute(); 
               System.out.print(".");
               try { TimeUnit.SECONDS.sleep(API_THROTTLE_SECONDS + parameterExtraThrottleSeconds);  }  catch (InterruptedException ie) {   }
            }

            // Repin if it hasn't been disabled            
            if (p.isPinned() && parameterPreservePins.booleanValue()) {

               if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "repinning " + z.getId() + " \n");  }                    
               authenticatedClient.statuses().pinStatus(z.getId()).execute(); 
               System.out.print(".");
               try { TimeUnit.SECONDS.sleep(API_THROTTLE_SECONDS + parameterExtraThrottleSeconds);  }  catch (InterruptedException ie) {   }
            }            
            
            repostedCounter++;
            System.out.println("");

         }  // end posting check (bookmarked only and date thresholds)

      }  // end for iterator through posts

      System.out.println("\nSuccessfully posted " + repostedCounter + " posts." + " \n");  

   } // end posting method


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