package io.github.mastodonContentMover;

import java.util.HashMap;
import java.util.TreeMap;
import java.util.Map;
import java.util.Collection;
import java.io.File;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAccessor;

import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Unmarshaller;


/**
*   Manages a collection of {@link io.github.mastodonContentMover.Post} objects
*   associated with an archive of Mastodon statuses, including loading them from file
*   on startup, maintaining indexes on them, and handling date/time processing related to
*   the internal {@code archiveId} used as the main reference for all {@link io.github.mastodonContentMover.Post}
*   objects.
*   <br /><br />
*   Uses the singleton model - for current (and anticipated) use cases, there should not
*   be a need for more than one {@link io.github.mastodonContentMover.PostArchive} object
*   to be instantiated simultaneously. This also means that synchronized methods can be
*   relied on to be synchronized on the one singleton object.
*   <br /><br />
*   The {@link io.github.mastodonContentMover.PostArchive} object itself does not persist
*   but instead uses the archive name provided on instantiation to locate a set of
*   persisted {@link io.github.mastodonContentMover.Post} objects in the filesystem and
*   load them into memory, populating two lookup indexes for them as it goes.
*   <br /><br />
*   Currently an archive can only be written once; a second attempt to write to the same
*   archive name will throw an exception. Later it will hopefully be possible to archive
*   incrementally, choosing whether to overwrite or add to existing data (with the add
*   perhaps doing something intelligent around modification dates of the XML file
*   compared to the most recent edited timestamp for a status on the Mastodon instance).
*   <br /><br />
*   @author Tokyo Outsider
*   @since 0.01.00
*/
public class PostArchive {

   private static final String POST_NOT_FOUND_EXCEPTION = "Post not found ";
   private static final String POST_ALREADY_ADDED_EXCEPTION = "Post already added ";
   private static final String ARCHIVE_ALREADY_IN_USE_EXCEPTION_PREFIX = "PostArchive is already being used for archive ";
   private static final String MASTODON_ID_SEPARATOR = "_";

   private static final String ID_DATE_TIME_PATTERN = "uuuuMMdd_HHmmss_SSSX";

   private static PostArchive singletonInstance = null;

   private String archiveName = null;   // used for the directory within which posts are stored
   private String directory = null;   // set to (Mover.getDataDirectory() + File.separator + archiveName) on initialization;
   private TreeMap<String, Post> postsByArchiveId = null;   // Need to specify a TreeMap here because this needs to be ordered
   private Map<String, Post> postsByMastodonId = null;   // Note, this will contain multiple ids pointing to the same post object

   private PostArchive() 
   {
     // do not use this.
   } 


   // We only do this if we weren't able to load the archive from a file, or if the file exists but we were told not to load from it
   private PostArchive(String name, boolean loadOK) throws JAXBException, IOException   
   {
      this.archiveName = name;

      this.directory = Mover.getDataDirectory() + File.separator + this.archiveName;

      this.postsByArchiveId = new TreeMap();    // A TreeMap sorts values by key - which we want here, because the key is the timestamp and we will want an interable sequence of posts to repost
      this.postsByMastodonId = new HashMap();   // We're not going to iterate over the posts by MastodonId, so lookup time is more important

      if (! new File(this.directory).exists()) {   // We're creating a new archive
         boolean success = new File(this.directory).mkdirs();
         if (!success) {
            throw new IOException(Mover.getErrorMessagePrefix() + "Could not create directory for archive " + archiveName + " or verify that it exists");
         }
      }
      else if (loadOK) {
         // Here we have been told we should load an existing archive (to repost to a new instance, for example, so we should do that and load from file)
         this.loadPosts();
      }
      else {
         // The file directory exists but we've been told not to load - at this point throw an exception because right now incremental archiving isn't implemented
         throw new IOException("Data directory for archive " + archiveName + " already exists but load parameter is set to 'false' ");
      }

   }


   /**
   *   Obtains a reference to the currently instantiated {@link io.github.mastodonContentMover.PostArchive}
   *   singleton object, if the specified archive name is the same, or instantiates one if
   *   that has not already been done.
   *   <br /><br />
   *   @param archiveName the name of the archive, also used for the directory in which
   *                      archive data is stored
   *   @param loadOK {@code true} to load existing archive data from the filesystem, or
   *                 {@code false} otherwise
   *   <br /><br />
   *   @return a reference to an instance of {@link io.github.mastodonContentMover.PostArchive} 
   *            for the specified archive name if no other {@link io.github.mastodonContentMover.PostArchive} 
   *            (for another archive name) was already instantiated
   *   <br /><br />
   *   @throws IllegalStateException if a {@link io.github.mastodonContentMover.PostArchive} 
   *                                 singleton is already instantiated for a different archive name
   *   @throws JAXBException (TODO: add more info here on when this happens)
   *   @throws IOException (TODO: add more info here on when this happens)
   *   <br /><br />
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected static synchronized PostArchive getSingletonInstance(String archiveName, boolean loadOK) throws JAXBException, IOException
   {
      if (singletonInstance == null) {
         singletonInstance = new PostArchive(archiveName, loadOK);
         if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "PostArchive get instance has " + singletonInstance.getPostCount() + " posts");   }
         return singletonInstance;
      }   
      else {
         // Check whether the archiveName is the same
         if ((singletonInstance.archiveName != null) && (singletonInstance.archiveName.equals(archiveName))) {
            if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "PostArchive get instance has " + singletonInstance.getPostCount() + " posts");   }
            return singletonInstance;
         }
         else {
            throw new IllegalStateException(ARCHIVE_ALREADY_IN_USE_EXCEPTION_PREFIX + singletonInstance.archiveName);
         }
      }

   }

   /**
   *   Obtain the filesystem directory name within which data for this {@link io.github.mastodonContentMover.PostArchive}
   *   instance is stored.
   *   <br /><br />
   *   @return a {@link java.lang.String} containing the filesystem directory name 
   *            within which data for this {@link io.github.mastodonContentMover.PostArchive}
   *            instance is stored
   *   <br /><br />
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected String getDirectory() {
      return directory;
   }




   /**
   *   Obtains a {@link java.util.Collection} view of all {@link io.github.mastodonContentMover.Post}
   *   objects held in this archive, from oldest to newest when iterated.
   *   <br /><br />
   *   An iterator on this {@link java.util.Collection} gives the {@link io.github.mastodonContentMover.Post}
   *   objects in ascending order of {@code archiveId} (with the underlying 
   *   implementation being a {@link java.util.TreeMap}.
   *   <br /><br />
   *   @return a {@link java.util.Collection} view of all {@link io.github.mastodonContentMover.Post}
   *            objects held in this archive, from oldest to newest when iterated
   *   <br /><br />
   *   @see java.util.TreeMap#values
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected Collection<Post> getAllPosts()
   {
      return this.postsByArchiveId.values();
   }

   /**
   *   Obtain the number of {@link io.github.mastodonContentMover.Post} objects
   *   currently stored within this {@link io.github.mastodonContentMover.PostArchive}
   *   instance.
   *   <br /><br />
   *   @return the number of {@link io.github.mastodonContentMover.Post} objects
   *            currently stored within this {@link io.github.mastodonContentMover.PostArchive}
   *            instance
   *   <br /><br />
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected int getPostCount() {
      return this.postsByArchiveId.size();
   }



   /**
   *   Creates a new {@link io.github.mastodonContentMover.Post} object in this archive
   *   for a status with the specified creation date and time, and the specified {@code mastodonId}.
   *   <br /><br />
   *   Suspect this should actually accept the instance hostname/address and instance id
   *   rather than the {@code mastodonId}, and then generate the {@code mastodonId} internally
   *   to this class (as it does with the archiveId from the creation date/time) to keep
   *   the logic where it should be. Should fix this (TODO)
   *   
   *   This method may either need to be substantially overhauled or another method
   *   created to check first whether a {@link io.github.mastodonContentMover.Post} object
   *   already exists in the archive for a given creation date and time when the tool is
   *   adjusted to allow for incremental archiving (TODO), when encountering a status during
   *   saving will need to then either overwrite or add to an existing {@link io.github.mastodonContentMover.Post}
   *   record if one already exists. Right now that never happens. One option is to add
   *   a parameter to specify incremental overwriting or addition (or create a whole
   *   separate method).
   *   <br /><br />
   *   @param createdAt an ISO 8601 compliant {@link java.lang.String} representing the
   *                    date and time in UTC (with the "Z") when a status was created 
   *   @param mid a {@code mastodonId}, comprised of the address or hostname for a 
   *              Mastodon instance and the internal id for a status on that instance
   *   @return a {@link io.github.mastodonContentMover.Post} object in this {@link io.github.mastodonContentMover.PostArchive}
   *            for the status creation date and {@code mastodonId} specified
   *   <br /><br />
   *   @throws JAXBException (TODO: add more info here on when this happens)
   *   @throws IOException (TODO: add more info here on when this happens)
   *   <br /><br />
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected Post addPost(String createdAt, String mid) throws JAXBException, IOException {

      if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + " creating id for " + createdAt + " \n");  }
      String id = getArchiveIdFromDateTime(createdAt); 
      if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "new id " + id + " \n");  }

      // TODO: Should we check whether it already exists here before adding, in both the archiveId index and mastodonId index?
      // And respond according to an overwrite/incremental addition flag?
      Post post = new Post(id, mid, this.singletonInstance);  

      return post;
   }



   /**
   *   Generates a MastodonContentMover internal {@code archiveId} based on an ISO 8601
   *   compliant {@link java.lang.String} representing the date and time in UTC 
   *   (with the "Z") when a status was created.
   *   <br /><br />
   *   @param isoDateTime an ISO 8601 compliant {@link java.lang.String} representing the
   *                    date and time in UTC (with the "Z") when a status was created 
   *   @return a MastodonContentMover internal {@code archiveId} corresponding to the
   *            status creation date and time specified
   *   <br /><br />
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   private static String getArchiveIdFromDateTime(String isoDateTime) {

     ZonedDateTime zdt = getZonedDateTimeFromIso(isoDateTime) ;  
     if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + " getIdFromDateTime for date " + zdt + " \n");  }
     return getIdFromZonedDateTime(zdt);
   }

   /**
   *   Adds a reference to a {@link io.github.mastodonContentMover.Post} object to
   *   the index maintained by this archive according to MastodonContentMover internal
   *   {@code archiveId}.
   *   <br /><br />
   *   TODO: Does this need to be separate to the method for {@code mastodonId}? Check
   *   and rationalise if needed. Exception should also be more specific.
   *   <br /><br />
   *   @param postObject the {@link io.github.mastodonContentMover.Post} for which a 
   *                     reference should be added to the {@code archiveId} index
   *   <br /><br />
   *   @throws RuntimeException if the index already contains a reference for the {@code archiveId} 
   *                            of the {@link io.github.mastodonContentMover.Post} object
   *                            passed as a parameter
   *   <br /><br />
   *   @see io.github.mastodonContentMover.Post#getArchiveId()
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected synchronized void registerPostByArchiveId(Post postObject) throws RuntimeException
   {
      if (postObject !=null) {
      
         String id = postObject.getArchiveId();
         if (this.postsByArchiveId.get(id) == null) {
            this.postsByArchiveId.put(id, postObject);  
         }
         else {
            throw new RuntimeException(POST_ALREADY_ADDED_EXCEPTION + " (" + id + ")");  // Should this be an IllegalStateException?
         }
      }
   }

   /**
   *   Obtains a {@code mastodonId} {@link java.lang.String} value, comprised of the
   *   Mastodon instance hostname/address concatenated with a separator and the id used
   *   for a status on that instance, used to index and retrieve {@link io.github.mastodonContentMover.Post}
   *   objects held in this archive and reconcile them with statuses on Mastodon instances.
   *   <br /><br />
   *   @param instanceAddress the hostname or address of a Mastodon instance
   *   @param id the instance-specific id used by Mastodon for a status on the specified
   *             instance
   *   @return the Mastodon instance hostname/address concatenated with a separator and
   *           the id used for a status on that instance
   *   <br /><br />
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected static String getMastodonId(String instanceAddress, String id)
   {
      return instanceAddress + MASTODON_ID_SEPARATOR + id;
   }

   /**
   *   Retrieves the Mastodon instance hostname or address from a concatenated {@code mastodonId}
   *   {@link java.lang.String} value that also contains a separator and the id used for 
   *   a status on that instance.
   *   <br /><br />
   *   @param mastodonId a Mastodon instance hostname/address concatenated with a separator and
   *                     the id used for a status on that instance
   *   <br /><br />
   *   @return the hostname or address of a Mastodon instance
   *   <br /><br />
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected static String getInstanceAddressFromMastodonId(String mastodonId) 
   {
      String[] parts = mastodonId.split(MASTODON_ID_SEPARATOR);
      if (parts.length > 0) {   return parts[0];   }
      else {   return null;   }  // If the separator wasn't found, it wasn't a valid mastodonId - should probably throw an exception here (TODO)
   }

   /**
   *   Retrieves the id used for a status on a Mastodon instance from a concatenated 
   *   {@code mastodonId} {@link java.lang.String} value that also contains the Mastodon 
   *   instance hostname or address.
   *   <br /><br />
   *   @param mastodonId a Mastodon instance hostname/address concatenated with a separator and
   *                     the id used for a status on that instance
   *   <br /><br />
   *   @return the instance-specific id used by Mastodon for a status on the specified instance
   *   <br /><br />
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected static String getInstanceIdFromMastodonId(String mastodonId) 
   {
      String[] parts = mastodonId.split(MASTODON_ID_SEPARATOR);
      if (parts.length > 1) {   return parts[1];   }
      else {   return null;   }  // If the separator wasn't found, it wasn't a valid mastodonId - should probably throw an exception here (TODO)
   }

   /**
   *   Adds a reference to a {@link io.github.mastodonContentMover.Post} object to
   *   the index maintained by this archive according to {@code mastodonId}, which is
   *   comprised of the address or hostname of the instance from which the {@link io.github.mastodonContentMover.Post} 
   *   was saved or has been reposted, and the internal id of the corresponding status on
   *   that instance.
   *   <br /><br />
   *   TODO: Does this need to be separate to the method for {@code archiveId}? Check
   *   and rationalise if needed. Exception should also be more specific.
   *   <br /><br />
   *   @param postObject the {@link io.github.mastodonContentMover.Post} for which a 
   *                     reference should be added to the {@code mastodonId} index
   *   @param mastodonId the {@code mastodonId} that should reference the {@link io.github.mastodonContentMover.Post}
   *                     object specified in the index
   *   <br /><br />
   *   @throws RuntimeException if the index already contains a reference for the {@code mastodonId} 
   *                            of the {@link io.github.mastodonContentMover.Post} object
   *                            passed as a parameter
   *   <br /><br />
   *   @see io.github.mastodonContentMover.PostArchive#getMastodonId(String instanceAddress, String postId)
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected synchronized void registerPostByMastodonId(Post postObject, String mastodonId) throws RuntimeException
   {
      if (postObject !=null) {
         if (this.postsByMastodonId.get(mastodonId) == null) {
            this.postsByMastodonId.put(mastodonId, postObject);  
         }
         else {
            throw new RuntimeException(POST_ALREADY_ADDED_EXCEPTION + " (" + mastodonId + ")");  // Should this be an IllegalStateException?
         }
      }
   }

   /**
   *   Appears to be a less-well implemented version of {@link io.github.mastodonContentMover.PostArchive#registerPostByMastodonId(Post postObject, String mastodonId)} 
   *   -- TODO: find where these are both called, check they genuinely are doing the same
   *   thing and rationalise!
   *   <br /><br />
   *   @param newId the {@code mastodonId}
   *   @param postObject the {@link io.github.mastodonContentMover.Post}
   *   <br /><br />
   *   @see io.github.mastodonContentMover.PostArchive#registerPostByMastodonId(Post postObject, String mastodonId)
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected synchronized void addMastodonId(String newId, Post postObject)
   {
      // this.postsByMastodonId.remove(oldId);               // TODO: Never do this until you successfully complete a full upload (write a separate method for that, to remove all bar the most recent id from the post objects)
      this.postsByMastodonId.put(newId, postObject);
   }

   /**
   *   Retrieves a reference to the {@link io.github.mastodonContentMover.Post} object 
   *   with the specified {@code archiveId}, based on the creation date and time of a 
   *   Mastodon status, stored in this {@link io.github.mastodonContentMover.PostArchive}
   *   <br /><br />
   *   @param id a valid {@code archiveId}
   *   <br /><br />
   *   @return a {@link io.github.mastodonContentMover.Post} object with the corresponding 
   *            {@code archiveId}, or {@code null} if no matching object is in the archive
   *   <br /><br />
   *   @see io.github.mastodonContentMover.PostArchive#getMastodonId(String instanceAddress, String postId)
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected synchronized Post getPostByArchiveId(String id)
   {
      return this.postsByArchiveId.get(id);
   }


   /**
   *   Retrieves a reference to the {@link io.github.mastodonContentMover.Post} object in
   *   this {@link io.github.mastodonContentMover.PostArchive} with the specified {@code mastodonId},
   *   which is comprised of the address or hostname of the instance from which the {@link io.github.mastodonContentMover.Post} 
   *   was saved or has been reposted, and the internal id of the corresponding status on
   *   that instance.
   *   <br /><br />
   *   @param mid a valid {@code mastodonId}
   *   <br /><br />
   *   @return a {@link io.github.mastodonContentMover.Post} object that was saved from
   *            or has been reposted to a Mastodon instance matching the specified 
   *            {@code mastodonId}, or {@code null} if no matching object is in the archive
   *   <br /><br />
   *   @see io.github.mastodonContentMover.PostArchive#getMastodonId(String instanceAddress, String postId)
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected synchronized Post getPostByMastodonId(String mid)
   {
      return this.postsByMastodonId.get(mid);
   }






   /**
   *   Determines whether a given {@code archiveId} is based on a date and time 
   *   before a specified ISO 8601 compliant {@link java.lang.String}.
   *   <br /><br />
   *   TODO: This method and parameter should be renamed since it's more about the 
   *   archiveId than a post. Also the debug needs to be clearer.
   *   <br /><br />
   *   Method is non-static so that if any processing related to a specific post in an
   *   archive instance is needed, it can be performed.
   *   <br /><br />
   *   @param postId the {@code archiveId} to compare to the specified date and time
   *   @param isoDateTime an ISO 8601 compliant {@link java.lang.String}
   *   @return true if the given {@code archiveId} represents a date and time before
   *           the specified ISO 8601 compliant {@link java.lang.String}, or false otherwise
   *   <br /><br />
   *   @see io.github.mastodonContentMover.Post#getArchiveId()
   *   @see io.github.mastodonContentMover.Post#isBefore(String isoDateTime) 
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected boolean postIsBeforeDateTime(String postId, String isoDateTime) {
     ZonedDateTime testDateTime = getZonedDateTimeFromIso(isoDateTime); 
     if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + " postPrecededDateTime for id " + postId + " and date " + testDateTime + " \n");  }

     ZonedDateTime postDateTime = getZonedDateTimeFromId(postId);  
     if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + " post was posted at " + postDateTime + " \n");  }

     return postDateTime.isBefore(testDateTime);
   }

   /**
   *   Determines whether a given {@code archiveId} is based on a date and time 
   *   after a specified ISO 8601 compliant {@link java.lang.String}.
   *   <br /><br />
   *   TODO: This method and parameter should be renamed since it's more about the 
   *   archiveId than a post. Also the debug needs to be clearer.
   *   <br /><br />
   *   Method is non-static so that if any processing related to a specific post in an
   *   archive instance is needed, it can be performed.
   *   <br /><br />
   *   @param postId the {@code archiveId} to compare to the specified date and time
   *   @param isoDateTime an ISO 8601 compliant {@link java.lang.String}
   *   @return true if the given {@code archiveId} represents a date and time after
   *           the specified ISO 8601 compliant {@link java.lang.String}, or false otherwise
   *   <br /><br />
   *   @see io.github.mastodonContentMover.Post#getArchiveId()
   *   @see io.github.mastodonContentMover.Post#isBefore(String isoDateTime) 
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected boolean postIsAfterDateTime(String postId, String isoDateTime) {
     ZonedDateTime testDateTime = getZonedDateTimeFromIso(isoDateTime);  
     if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + " postPrecededDateTime for id " + postId + " and date " + testDateTime + " \n");  }

     ZonedDateTime postDateTime = getZonedDateTimeFromId(postId);  
     if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + " post was posted at " + postDateTime + " \n");  }

     return postDateTime.isAfter(testDateTime);
   }

   private static ZonedDateTime getZonedDateTimeFromIso(String isoDateTime) {
      return ZonedDateTime.from(DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(isoDateTime));  
   }

   private static ZonedDateTime getZonedDateTimeFromId(String postId) {
      return ZonedDateTime.from(DateTimeFormatter.ofPattern(ID_DATE_TIME_PATTERN).parse(postId));  
   }

   private static String getIdFromZonedDateTime(ZonedDateTime zdt) {
      return DateTimeFormatter.ofPattern(ID_DATE_TIME_PATTERN).format(zdt);  
   }



   // Posts are loaded by iterating through directories to load each individually, so that any 
   // changes made manually to the data are fully reflected. Directory and filename paths are
   // set when loaded for the same reason, with these not being saved to the XML (instead being
   // marked as transient) as manual changes to the archive data would then break them.
   private void loadPosts() throws JAXBException, IOException       
   { 
         // Iterate through our directory - for each subdirectory, create a Post object. 
         File archiveRoot = new File(this.directory);
         if (archiveRoot.isDirectory()) {

            File[] postDirectories = archiveRoot.listFiles();

            for (File pd : postDirectories) {

               if (pd.isDirectory()) {
                  String id = pd.getName();
                  if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "loading post id " + id);   }
                  Post p = new Post(id, null, this); // This should register itself with our indices
               }
            }
            if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "loaded " + this.getPostCount() + " posts");  }

         }
         else {
            if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + " Archive directory not found when loading posts");  }
            // Something weird has happened because this should be a directory - TODO: throw an exception here and tidy the debug/output
         }
   }

}