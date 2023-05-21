package io.github.mastodonContentMover;

import java.io.File;
import java.io.IOException;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.util.List;
import java.util.ArrayList;
import java.util.Queue;
import java.util.LinkedList; // implementation of Queue
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.net.URL;
import java.net.MalformedURLException;

//   JAXB for persistence
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlTransient;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Unmarshaller;
import jakarta.xml.bind.Marshaller;


/**
*   Holds in an XML-persistable object all the data associated with a Mastodon status,
*   using {@link io.github.mastodonContentMover.MediaFile} objects to store metadata
*   associated with media files.
*   <br /><br />
*   @see io.github.mastodonContentMover.MediaFile
*   @author Tokyo Outsider
*   @since 0.01.00
*/
@XmlRootElement(name = "post")
@XmlAccessorType(XmlAccessType.FIELD)
public class Post {

   private String archiveId;   // The MastodonContentMover internal index for each post, based on the date and time it was created
   private Queue<String> mastodonIds; // The ids used for this post on various Mastodon instances, stored as Strings the include both the instance address/hostname and the id on the instance that uses that hostname. TODO: This may only need to be an ArrayList rather than a Queue - reassess once resumeable posting and clear-down on completion is implemented.
   private String text;
   private String visibility;
   private boolean sensitive;
   private String spoilerText;
   private ArrayList<MediaFile> mediaFiles; 
   private String inReplyToArchiveId;   // The MastodonContentMover internal archiveId of the post being replied to
   private String reblogUrl;   // 
   private String language;
   private boolean favourited;
   private boolean bookmarked;
   private boolean pinned;
   private long favouritesCount;
   private long reblogsCount;

//  private Object[] emojis;   // TODO (later)
//  TODO: Polls? Not sure how these could ever be saved.


   // Transient values are not persisted. Directory and filename are not persisted 
   // because it is possible someone might manually adjust the directory or data outside
   // of the tool, so it is read back in on load each time the tool is run.
   @XmlTransient
   private String directory = null;
   @XmlTransient
   private String filename = null;

   @XmlTransient
   private PostArchive postArchive = null;
   @XmlTransient
   private boolean saveOnEveryChange = true;

   // Constants for JAXB codes used for various encodings. Probably don't need all of
   // them but figuring them out took some trial and error I'd rather not have to repeat.
   private static final String JAXB_ENCODING_UTF16_LITTLE_ENDIAN = "UTF-16LE";
   private static final String JAXB_ENCODING_UTF16_BIG_ENDIAN = "UTF-16BE";
   private static final String JAXB_ENCODING_UTF16_UNSPECIFIED_ENDIAN = "UTF-16";
   private static final String JAXB_ENCODING = JAXB_ENCODING_UTF16_UNSPECIFIED_ENDIAN;




   private Post() {
   // don't.
   }   

   /**
   *   Loads the {@link io.github.mastodonContentMover.Post} object with the specified
   *   archiveId in the specified {@link io.github.mastodonContentMover.PostArchive} from
   *   file if it is available, or creates a new {@link io.github.mastodonContentMover.Post} 
   *   object if no data is found for that archiveId on the filesystem within the given
   *   data directory for the specified {@link io.github.mastodonContentMover.PostArchive}.
   *   <br /><br />
   *   Currently the {@code mastodonId} parameter can be null, but it would be better if 
   *   a separate constructor was made without the {@code mastodonId} parameter. This
   *   constructor could then call that constructor and perform the processing required
   *   for the {@code mastodonId} specified after receiving the {@link io.github.mastodonContentMover.Post}
   *   object from that constructor (including registering it with the archive). Then
   *   parameters would never need to be null. Also the postId parameter would really be
   *   better if it was named archiveId. (TODO)
   *   <br /><br />
   *   Would also be good to mention in this doc info when this is likely to be called (TODO)
   *   <br /><br />
   *   @param postId the MastodonContentMover internal archiveId for this post
   *   @param mastodonId the id for this post on a specific Mastodon instance
   *   @param pa the {@link io.github.mastodonContentMover.PostArchive} from which to
   *             load the {@link io.github.mastodonContentMover.Post} object
   *   <br /><br />
   *   @throws JAXBException (TODO: add more info here on when this happens)
   *   @throws IOException (TODO: add more info here on when this happens)
   *   @throws IllegalStateException (TODO: add more info here on when this happens)
   *   <br /><br />
   *   @see io.github.mastodonContentMover.PostArchive#getMastodonId(String instanceAddress, String postId)
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected Post(String postId, String mastodonId, PostArchive pa) throws JAXBException, IOException
   {
      this.postArchive = pa;
      this.directory = this.postArchive.getDirectory() + File.separator + postId;
      this.filename = this.directory + File.separator + postId + ".xml";   // TODO: Make this file extension a final constant, and check whether it is platform independent
      if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "this.filename " + this.filename);  }

      // Figure out what the filename should be, check the directories exist (or make if necessary, but the archive one should be made by archive) and throw an exception if there's a problem so the object is never instantiated without the proper file access.
      if (! new File(this.directory).exists()) {
         boolean success = new File(this.directory).mkdirs();
         if (!success) {
            throw new IOException(Mover.getErrorMessagePrefix() + "Could not create directory for post " + archiveId + " or verify that it exists");
         }
         this.archiveId = postId;
         this.mastodonIds = new LinkedList();
         if (mastodonId != null) {  this.mastodonIds.add(mastodonId);  }
         this.writeToFile();  // We only need to do this if we haven't just loaded this object from a file
      }
      else {
         // Unmarshalling internally because then we keep the logic on how the filename etc. is determined within this class, where it should be - it marshalls itself, it should unmarshall itself too
         JAXBContext jaxbContext = JAXBContext.newInstance(Post.class);
         Unmarshaller jaxbUnmarshaller = jaxbContext.createUnmarshaller();
         Post loaded = (Post)jaxbUnmarshaller.unmarshal(new File(this.filename));
         // Copy the data into the object this constructor was called to create (this seems to be the only way to unmarshall within a constructor of the class that is being unmarshalled)
         if (! loaded.archiveId.equals(postId)) {   throw new IllegalStateException ("Loaded Post object has a different ID to request - something very weird happened here");   }
         else { 
            this.archiveId = loaded.archiveId;
         }
         this.mastodonIds = loaded.mastodonIds; // TODO: Should check here whether the mastodonId provided to this method (if not null) is already among the loaded ids, and add if necessary
         this.text = loaded.text;
         this.visibility = loaded.visibility;
         this.sensitive = loaded.sensitive;
         this.spoilerText = loaded.spoilerText;
         this.mediaFiles = loaded.mediaFiles;
         this.inReplyToArchiveId = loaded.inReplyToArchiveId;
         this.reblogUrl = loaded.reblogUrl;
         this.language = loaded.language;
         this.favourited = loaded.favourited;
         this.bookmarked = loaded.bookmarked;
         this.pinned = loaded.pinned;
         this.favouritesCount = loaded.favouritesCount;
         this.reblogsCount = loaded.reblogsCount;
         //  private Object[] emojis;     TODO: Add this later

         // Iterate through our media files to set the directory we now find ourselves in, since it may change between saving the archive and loading it and is transient
         if (this.mediaFiles != null) {
            for (MediaFile m: this.mediaFiles) {
               m.setDirectory(this.directory);
            }
         }

      }
      // Register ourselves with the archive (since we have the reference)
      // Hopefully neither this nor registering the mastodon id will throw an exception,
      // because before this is called the archive should already have checked if a post 
      // exists for these ids, and decided what to do based on an overwrite/incremental 
      // archive flag (that doesn't exist yet) - but this code should be made safer so
      // it handles things more elegantly if that's not the case (TODO!)
      this.postArchive.registerPostByArchiveId(this);
      // Iterate through our mastodonIds and register those, too
      if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "Mastodon ids for post " + this.archiveId + ": " + this.mastodonIds.size());  }
      for (String m: this.mastodonIds) {
            this.postArchive.registerPostByMastodonId(this, m);
      if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "Registering post " + this.text + " with mastodonId " + m);  }
      }

      // The other object that was loaded during unmarshalling should now just be garbage
      // collected and the constructor will return this object, fully populated, instead
   }   


   /**
   *   Gives the MastodonContentMover internal id for this {@link io.github.mastodonContentMover.Post} 
   *   object, based on the creation date of the Mastodon post that was first downloaded
   *   and from which this {@link io.github.mastodonContentMover.Post} object, in this 
   *   archive, was first created. 
   *   <br /><br />
   *   For example, if you create an archive from "instance #1", use that archive to 
   *   populate "instance #2", then create a fresh archive from "instance #2", the date
   *   that will be used for this comparison will be the date you used your initial 
   *   archive to populate "instance #2" (not the date  you first created the post on 
   *   "instance #1").
   *   <br /><br />
   *   @return the archiveId of this {@link io.github.mastodonContentMover.Post} object
   *   <br /><br />
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected String getArchiveId()
   {
      return this.archiveId;
   }

   /**
   *   Returns the most recent Mastodon instance id used for this {@link io.github.mastodonContentMover.Post} on the instance
   *   specified as a parameter.
   *   <br /><br />
   *   @param i the instance address or hostname, specified as a {@link java.lang.String}
   *   @return the most recent Mastodon instance id, comprised of the address or hostname for that
   *           instance and the internal id for this post on that instance, that was
   *           retrieved when reposting this {@link io.github.mastodonContentMover.Post} 
   *           from this archive to the instance specified.
   *   <br /><br />
   *   @see io.github.mastodonContentMover.PostArchive#getMastodonId(String instanceAddress, String postId)
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected String getLatestMastodonId(String i)
   {
      // Iterate through all our known Mastodon Ids and find the most recent for the instance specified
      // Note - a Queue (our Mastodon ids object) is FIFO, and even an ArrayList would be too, so the last one we iterate over should be the most recent.
      // (It's not clear whether Mastodon instance ids are sequential, and it shouldn't really matter for us anyway - seems safe to presume we want the most recent one we touched)
      String instanceId = null;
      for(String d : mastodonIds){
         if (PostArchive.getInstanceAddressFromMastodonId(d).toLowerCase().equals(i.toLowerCase()))
         {
            instanceId = d;
         }
      }
      return instanceId;
   } 


   /**
   *   Associates with this {@link io.github.mastodonContentMover.Post} object a new 
   *   mastodonId {@link java.lang.String} value, comprised of the Mastodon instance
   *   hostname/address concatenated with the id used for the status on that instance,
   *   and saves the change to file if persistance has not been paused.
   *   <br /><br />
   *   @param mi a Mastodon instance hostname/address concatenated with a separator and
   *             the id used for the status on that instance
   *   <br /><br />
   *   @throws JAXBException (TODO: add more info here on when this happens)
   *   @throws IOException (TODO: add more info here on when this happens)
   *   <br /><br />
   *   @see io.github.mastodonContentMover.PostArchive#getMastodonId(String instanceAddress, String postId)
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected synchronized void addMastodonId(String mi) throws JAXBException, IOException
   {
      this.mastodonIds.add(mi);

      // The PostArchive maintains an index of posts by mastodonId, so we need to tell it as this call has come direct to the data object
      this.postArchive.addMastodonId(mi, this);

      if (saveOnEveryChange) {
         this.writeToFile();
      }
   }

   /**
   *   Obtains an ordered primitive {@link java.lang.String} array of all the mastodonId
   *   valued associated with this {@link io.github.mastodonContentMover.Post} object, 
   *   each comprised of a Mastodon instance hostname/address concatenated with the id 
   *   used for the status on that instance.
   *   <br /><br />
   *   The array is ordered according to the sequence in which this {@link io.github.mastodonContentMover.Post}
   *   object was reposted to each Mastodon instance. The same Mastodon instance may 
   *   appear more than once in the list (although presumably not with the same id - if
   *   that has happened it probably means something has gone wrong, as the alternative
   *   of the same status having been assigned the same numeric id by the same instance
   *   on two separate occasions is highly unlikely - TODO: check for duplicates on adding?)
   *   <br /><br />
   *   @return an array of {@link java.lang.String} objects each containing a Mastodon 
   *            instance hostname/address concatenated with a separator and the id used 
   *            for the status on that instance
   *   <br /><br />
   *   @see io.github.mastodonContentMover.PostArchive#getMastodonId(String instanceAddress, String postId)
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected String[] getMastodonIds()
   {
      return (String[])this.mastodonIds.toArray();
   }


   /**
   *   Halts the saving to file of changes to this {@link io.github.mastodonContentMover.Post} 
   *   object as and when they are made, until this behaviour is restored with {@see io.github.mastodonContentMover.Post#resumePersistence()}.
   *   <br /><br />
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected synchronized void pausePersistence()
   {
      this.saveOnEveryChange = false;
   }

   /**
   *   Resumes the saving to file of changes to this {@link io.github.mastodonContentMover.Post} 
   *   object as and when they are made, and saves its current state to file.
   *   <br /><br />
   *   @throws JAXBException (TODO: add more info here on when this happens)
   *   @throws IOException (TODO: add more info here on when this happens)
   *   <br /><br />
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected synchronized void resumePersistence() throws JAXBException, IOException
   {
      this.saveOnEveryChange = true;
      this.writeToFile();   // Presumably some things have happened that now need to be saved
   }


   /**
   *   Stores the Mastodon StatusSource, which is the plain text used to compose the
   *   status, to this {@link io.github.mastodonContentMover.Post} object, and saves
   *   the change to file if persistance has not been paused.
   *   <br /><br />
   *   @param pt the plain text that comprises the body of this post
   *   <br /><br />
   *   @throws JAXBException (TODO: add more info here on when this happens)
   *   @throws IOException (TODO: add more info here on when this happens)
   *   <br /><br />
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected synchronized void setText(String pt) throws JAXBException, IOException
   {
      this.text = pt;

      if (saveOnEveryChange) {
         this.writeToFile();
      }
   }

   /**
   *   Retrieves the plain text that comprises the body of the Mastodon status this
   *   {@link io.github.mastodonContentMover.Post} object represents.
   *   <br /><br />
   *   @return the body text of this {@link io.github.mastodonContentMover.Post} object in plain text
   *   <br /><br />
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected String getText()
   {
      return this.text;
   }

   /**
   *   Stores the value of the Mastodon {@code spoiler_text} field in this {@link io.github.mastodonContentMover.Post}
   *   object, and saves the change to file if persistance has not been paused.
   *   <br /><br />
   *   @param st the text used to apply a spoiler or content warning on this post, also
   *             known elsewhere on ActivityPub as a summary
   *   <br /><br />
   *   @throws JAXBException (TODO: add more info here on when this happens)
   *   @throws IOException (TODO: add more info here on when this happens)
   *   <br /><br />
   *   @see <a href="https://docs.joinmastodon.org/entities/Status/#spoiler_text">{@code spoiler_text} for the Status object in the Mastodon API documentation</a>
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected synchronized void setSpoilerText(String st) throws JAXBException, IOException
   {
      this.spoilerText = st;

      if (saveOnEveryChange) {
         this.writeToFile();
      }
   }

   /**
   *   Retrieves the value of the Mastodon {@code spoiler_text} field stored for this
   *   {@link io.github.mastodonContentMover.Post} object.
   *   <br /><br />
   *   @return the text used to apply a spoiler or content warning on this post, also
   *             known elsewhere on ActivityPub as a summary
   *   <br /><br />
   *   @see <a href="https://docs.joinmastodon.org/entities/Status/#spoiler_text">{@code spoiler_text} for the Status object in the Mastodon API documentation</a>
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected String getSpoilerText()
   {
      return this.spoilerText;
   }

   /**
   *   Stores as a {@link java.lang.String} value the visibility of the Mastodon status 
   *   represented by this {@link io.github.mastodonContentMover.Post} object, and saves 
   *   the change to file if persistance has not been paused.
   *   <br /><br />
   *   As specified in the Mastodon API documentation, and must match the value used in 
   *   the BigBone API used by this tool to interact with the Mastodon API.
   *   <br /><br />
   *   At time of writing, values are:
   *   <br /><br />
   *   <ul>
   *      <li>public = Visible to everyone, shown in public timelines.</li>
   *      <li>unlisted = Visible to public, but not included in public timelines.</li>
   *      <li>private = Visible to followers only, and to any mentioned users.</li>
   *      <li>direct = Visible only to mentioned users.</li>
   *   </ul>
   *   <br /><br />
   *   @param v the visibility of the status represented by this {@link io.github.mastodonContentMover.Post}
   *            object 
   *   <br /><br />
   *   @throws JAXBException (TODO: add more info here on when this happens)
   *   @throws IOException (TODO: add more info here on when this happens)
   *   <br /><br />
   *   @see <a href="https://docs.joinmastodon.org/entities/Status/#visibility">{@code visibility} for the Status object in the Mastodon API documentation</a>
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected synchronized void setVisibility(String v) throws JAXBException, IOException
   {
      this.visibility = v.toLowerCase();

      if (saveOnEveryChange) {
         this.writeToFile();
      }
   }

   /**
   *   Obtains as a {@link java.lang.String} value the visibility of the Mastodon status 
   *   represented by this {@link io.github.mastodonContentMover.Post} object.
   *   <br /><br />
   *   As specified in the Mastodon API documentation, and must match the value used in 
   *   the BigBone API used by this tool to interact with the Mastodon API.
   *   <br /><br />
   *   At time of writing, values are:
   *   <br /><br />
   *   <ul>
   *      <li>public = Visible to everyone, shown in public timelines.</li>
   *      <li>unlisted = Visible to public, but not included in public timelines.</li>
   *      <li>private = Visible to followers only, and to any mentioned users.</li>
   *      <li>direct = Visible only to mentioned users.</li>
   *   </ul>
   *   <br /><br />
   *   @return the visibility of the status represented by this {@link io.github.mastodonContentMover.Post}
   *            object
   *   <br /><br />
   *   @see io.github.mastodonContentMover.Post#setVisibility(String v)
   *   @see <a href="https://docs.joinmastodon.org/entities/Status/#visibility">{@code visibility} for the Status object in the Mastodon API documentation</a>
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected String getVisibility()
   {
     return this.visibility;
   }

   /**
   *   Stores as a primitive {@code boolean} value the sensitivity of the Mastodon status 
   *   represented by this {@link io.github.mastodonContentMover.Post} object, and saves 
   *   the change to file if persistance has not been paused.
   *   <br /><br />
   *   @param is {@code true} for statuses that should be concealed beneath a spoiler or
   *             content warning, or {@code false} where that is not the case
   *   <br /><br />
   *   @throws JAXBException (TODO: add more info here on when this happens)
   *   @throws IOException (TODO: add more info here on when this happens)
   *   <br /><br />
   *   @see <a href="https://docs.joinmastodon.org/entities/Status/#sensitive">{@code sensitive} for the Status object in the Mastodon API documentation</a>
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected synchronized void setIsSensitive(boolean is) throws JAXBException, IOException
   {
      this.sensitive = is;

      if (saveOnEveryChange) {
         this.writeToFile();
      }
   }

   /**
   *   Obtains as a primitive {@code boolean} value the sensitivity of the Mastodon status 
   *   represented by this {@link io.github.mastodonContentMover.Post} object.
   *   <br /><br />
   *   @return {@code true} for statuses that should be concealed beneath a spoiler or
   *             content warning, or {@code false} where that is not the case
   *   <br /><br />
   *   @see <a href="https://docs.joinmastodon.org/entities/Status/#sensitive">{@code sensitive} for the Status object in the Mastodon API documentation</a>
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected boolean isSensitive()
   {
     return this.sensitive;
   }

   /**
   *   Stores the MastodonContentMover internal archiveId for a {@link io.github.mastodonContentMover.Post}
   *   object to which this {@link io.github.mastodonContentMover.Post} object is a
   *   reply, and saves the change to file if persistance has not been paused.
   *   <br /><br />
   *   This tool only links self-replies in this way, not replies to statuses by other users.
   *   <br /><br />
   *   @param irtai the {@code archiveId} for a {@link io.github.mastodonContentMover.Post}
   *                object to which this {@link io.github.mastodonContentMover.Post}
   *                object is a reply
   *   <br /><br />
   *   @throws JAXBException (TODO: add more info here on when this happens)
   *   @throws IOException (TODO: add more info here on when this happens)
   *   <br /><br />
   *   @see io.github.mastodonContentMover.Post#getArchiveId()
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected synchronized void setInReplyToArchiveId(String irtai) throws JAXBException, IOException
   {
      this.inReplyToArchiveId = irtai;

      if (saveOnEveryChange) {
         this.writeToFile();
      }
   }

   /**
   *   Obtains the MastodonContentMover internal archiveId for a {@link io.github.mastodonContentMover.Post}
   *   object to which this {@link io.github.mastodonContentMover.Post} object is a
   *   reply.
   *   <br /><br />
   *   This tool only links self-replies in this way, not replies to statuses by other users.
   *   <br /><br />
   *   @return the {@code archiveId} for a {@link io.github.mastodonContentMover.Post}
   *            object to which this {@link io.github.mastodonContentMover.Post}
   *            object is a reply
   *   <br /><br />
   *   @see io.github.mastodonContentMover.Post#getArchiveId()
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected String getInReplyToArchiveId()
   {
     return this.inReplyToArchiveId;
   }

   /**
   *   Stores as a {@link java.lang.String} the URL of a Mastodon status that was 
   *   reblogged in the status this {@link io.github.mastodonContentMover.Post} object
   *   represents, and saves the change to file if persistance has not been paused.
   *   <br /><br />
   *   @param ru the url of a Mastodon status reblogged in this {@link io.github.mastodonContentMover.Post}
   *                object
   *   <br /><br />
   *   @throws JAXBException (TODO: add more info here on when this happens)
   *   @throws IOException (TODO: add more info here on when this happens)
   *   <br /><br />
   *   @see <a href="https://docs.joinmastodon.org/entities/Status/#reblog">{@code reblog} for the Status object in the Mastodon API documentation</a>
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected synchronized void setReblogUrl(String ru) throws JAXBException, IOException
   {
      this.reblogUrl = ru;

      if (saveOnEveryChange) {
         this.writeToFile();
      }
   }

   /**
   *   Obtains as a {@link java.lang.String} the URL of a Mastodon status that was 
   *   reblogged in the status this {@link io.github.mastodonContentMover.Post} object
   *   represents.
   *   <br /><br />
   *   In order to repost this reblogged status, it is necessary to search for this URL
   *   on the Mastodon instance where this {@link io.github.mastodonContentMover.Post} 
   *   object is being reposted, using "resolve=true", to get the status id on that 
   *   instance. (This is not implemented yet - TODO!)
   *   <br /><br />
   *   @return the url of a Mastodon status reblogged in this {@link io.github.mastodonContentMover.Post}
   *                object
   *   <br /><br />
   *   @see <a href="https://docs.joinmastodon.org/entities/Status/#reblog">{@code reblog} for the Status object in the Mastodon API documentation</a>
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected String getReblogUrl()
   {
     return this.reblogUrl;
   }

   /**
   *   Stores as a {@link java.lang.String} value an ISO 639 Part 1 two-letter language 
   *   code for the Mastodon status represented by this {@link io.github.mastodonContentMover.Post}
   *   object, and saves the change to file if persistance has not been paused.
   *   <br /><br />
   *   As specified in the Mastodon API documentation, and must match the value used in 
   *   the BigBone API used by this tool to interact with the Mastodon API.
   *   <br /><br />
   *   @param l an ISO 639 Part 1 two-letter language code for the status represented by
   *            this {@link io.github.mastodonContentMover.Post} object
   *   <br /><br />
   *   @throws JAXBException (TODO: add more info here on when this happens)
   *   @throws IOException (TODO: add more info here on when this happens)
   *   <br /><br />
   *   @see <a href="https://docs.joinmastodon.org/entities/Status/#language">{@code language} for the Status object in the Mastodon API documentation</a>
   *   @see <a href="https://en.wikipedia.org/wiki/List_of_ISO_639-1_codes">List of ISO 639-1 codes at Wikipedia</a>
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected synchronized void setLanguage(String l) throws JAXBException, IOException
   {
      this.language = l;

      if (saveOnEveryChange) {
         this.writeToFile();
      }
   }

   /**
   *   Obtains as a {@link java.lang.String} value an ISO 639 Part 1 two-letter language 
   *   code for the Mastodon status represented by this {@link io.github.mastodonContentMover.Post}
   *   object.
   *   <br /><br />
   *   As specified in the Mastodon API documentation, and must match the value used in 
   *   the BigBone API used by this tool to interact with the Mastodon API.
   *   <br /><br />
   *   @return an ISO 639 Part 1 two-letter language code for the status represented by
   *            this {@link io.github.mastodonContentMover.Post} object
   *   <br /><br />
   *   @see <a href="https://docs.joinmastodon.org/entities/Status/#language">{@code language} for the Status object in the Mastodon API documentation</a>
   *   @see <a href="https://en.wikipedia.org/wiki/List_of_ISO_639-1_codes">List of ISO 639-1 codes at Wikipedia</a>
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected String getLanguage()
   {
     return this.language;
   }

   /**
   *   Stores as a primitive {@code boolean} whether the Mastodon status represented by
   *   this {@link io.github.mastodonContentMover.Post} object is favourited by the account
   *   used to save it to the archive, and saves the change to file if persistance has
   *   not been paused.
   *   <br /><br />
   *   This field may later be used to determine whether a status created when this {@link io.github.mastodonContentMover.Post} 
   *   is reposted should be favourited again, based also on the parameters specified
   *   when the tool is run. (TODO)
   *   <br /><br />
   *   @param ifav {@code true} for statuses that have been favourited by the account
   *               used to save it to the archive, or {@code false} otherwise
   *   <br /><br />
   *   @throws JAXBException (TODO: add more info here on when this happens)
   *   @throws IOException (TODO: add more info here on when this happens)
   *   <br /><br />
   *   @see <a href="https://docs.joinmastodon.org/entities/Status/#favourited">{@code favourited} for the Status object in the Mastodon API documentation</a>
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected synchronized void setIsFavourited(boolean ifav) throws JAXBException, IOException
   {
      this.favourited = ifav;

      if (saveOnEveryChange) {
         this.writeToFile();
      }
   }

   /**
   *   Obtains a primitive {@code boolean} value that indicates whether the Mastodon 
   *   status represented by this {@link io.github.mastodonContentMover.Post} object is
   *   favourited by the account used to save it to the archive.
   *   <br /><br />
   *   This field may later be used to determine whether a status created when this {@link io.github.mastodonContentMover.Post} 
   *   is reposted should be favourited again, based also on the parameters specified
   *   when the tool is run. (TODO)
   *   <br /><br />
   *   @return {@code true} for statuses that have been favourited by the account
   *               used to save it to the archive, or {@code false} otherwise
   *   <br /><br />
   *   @see <a href="https://docs.joinmastodon.org/entities/Status/#favourited">{@code favourited} for the Status object in the Mastodon API documentation</a>
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected boolean isFavourited()
   {
     return this.favourited;
   }

   /**
   *   Stores as a primitive {@code boolean} whether the Mastodon status represented by
   *   this {@link io.github.mastodonContentMover.Post} object is bookmarked by the account
   *   used to save it to the archive, and saves the change to file if persistance has
   *   not been paused.
   *   <br /><br />
   *   This field is used to determine whether a status created when this {@link io.github.mastodonContentMover.Post} 
   *   is reposted should be bookmarked again, based also on the parameters specified
   *   when the tool is run.
   *   <br /><br />
   *   @param ib {@code true} for statuses that have been bookmarked by the account
   *             used to save it to the archive, or {@code false} otherwise
   *   <br /><br />
   *   @throws JAXBException (TODO: add more info here on when this happens)
   *   @throws IOException (TODO: add more info here on when this happens)
   *   <br /><br />
   *   @see <a href="https://docs.joinmastodon.org/entities/Status/#bookmarked">{@code bookmarked} for the Status object in the Mastodon API documentation</a>
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected synchronized void setIsBookmarked(boolean ib) throws JAXBException, IOException
   {
      this.bookmarked = ib;

      if (saveOnEveryChange) {
         this.writeToFile();
      }
   }

   /**
   *   Obtains a primitive {@code boolean} value that indicates whether the Mastodon 
   *   status represented by this {@link io.github.mastodonContentMover.Post} object is
   *   bookmarked by the account used to save it to the archive.
   *   <br /><br />
   *   This field is used to determine whether a status created when this {@link io.github.mastodonContentMover.Post} 
   *   is reposted should be bookmarked again, based also on the parameters specified
   *   when the tool is run.
   *   <br /><br />
   *   @return {@code true} for statuses that have been bookmarked by the account
   *               used to save it to the archive, or {@code false} otherwise
   *   <br /><br />
   *   @see <a href="https://docs.joinmastodon.org/entities/Status/#bookmarked">{@code bookmarked} for the Status object in the Mastodon API documentation</a>
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected boolean isBookmarked()
   {
     return this.bookmarked;
   }

   /**
   *   Stores as a primitive {@code boolean} whether the Mastodon status represented by
   *   this {@link io.github.mastodonContentMover.Post} object is pinned by the account
   *   used to save it to the archive, and saves the change to file if persistance has
   *   not been paused.
   *   <br /><br />
   *   This field is used to determine whether a status created when this {@link io.github.mastodonContentMover.Post} 
   *   is reposted should be pinned again, based also on the parameters specified
   *   when the tool is run.
   *   <br /><br />
   *   @param ip {@code true} for statuses that have been pinned by the account
   *             used to save it to the archive, or {@code false} otherwise
   *   <br /><br />
   *   @throws JAXBException (TODO: add more info here on when this happens)
   *   @throws IOException (TODO: add more info here on when this happens)
   *   <br /><br />
   *   @see <a href="https://docs.joinmastodon.org/entities/Status/#pinned">{@code pinned} for the Status object in the Mastodon API documentation</a>
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected synchronized void setIsPinned(boolean ip) throws JAXBException, IOException
   {
      this.pinned = ip;

      if (saveOnEveryChange) {
         this.writeToFile();
      }
   }

   /**
   *   Obtains a primitive {@code boolean} value that indicates whether the Mastodon 
   *   status represented by this {@link io.github.mastodonContentMover.Post} object is
   *   pinned by the account used to save it to the archive.
   *   <br /><br />
   *   This field is used to determine whether a status created when this {@link io.github.mastodonContentMover.Post} 
   *   is reposted should be pinned again, based also on the parameters specified
   *   when the tool is run.
   *   <br /><br />
   *   @return {@code true} for statuses that have been pinned by the account
   *               used to save it to the archive, or {@code false} otherwise
   *   <br /><br />
   *   @see <a href="https://docs.joinmastodon.org/entities/Status/#pinned">{@code pinned} for the Status object in the Mastodon API documentation</a>
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected boolean isPinned()
   {
     return this.pinned;
   }

   /**
   *   Stores as a primitive {@code long} the number of favourites for the Mastodon 
   *   status represented by this {@link io.github.mastodonContentMover.Post} object, 
   *   and saves the change to file if persistance has not been paused.
   *   <br /><br />
   *   This field may later be used to determine whether or not to repost this {@link io.github.mastodonContentMover.Post} 
   *   object, based on a threshold specified when the tool is run. (TODO)
   *   <br /><br />
   *   @param fc the number of favourites for the Mastodon status represented by this 
   *             {@link io.github.mastodonContentMover.Post} object when it was saved to
   *             the archive
   *   <br /><br />
   *   @throws JAXBException (TODO: add more info here on when this happens)
   *   @throws IOException (TODO: add more info here on when this happens)
   *   <br /><br />
   *   @see <a href="https://docs.joinmastodon.org/entities/Status/#favorites_count">{@code favorites_count} for the Status object in the Mastodon API documentation</a>
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected synchronized void setFavouritesCount(long fc) throws JAXBException, IOException
   {
      this.favouritesCount = fc;

      if (saveOnEveryChange) {
         this.writeToFile();
      }
   }

   /**
   *   Obtains as a primitive {@code long} the number of a count of favourites for the 
   *   status represented by this {@link io.github.mastodonContentMover.Post} object.
   *   <br /><br />
   *   This may later be used to determine whether or not to repost this {@link io.github.mastodonContentMover.Post} 
   *   object, based on a threshold specified when the tool is run. (TODO)
   *   <br /><br />
   *   @return the count of favourites for the Mastodon status represented by this 
   *            {@link io.github.mastodonContentMover.Post} object when it was saved to
   *            the archive
   *   <br /><br />
   *   @see <a href="https://docs.joinmastodon.org/entities/Status/#favorites_count">{@code favorites_count} for the Status object in the Mastodon API documentation</a>
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected long getFavouritesCount()
   {
     return this.favouritesCount;
   }

   /**
   *   Stores as a primitive {@code long} the number of times the Mastodon status 
   *   represented by this {@link io.github.mastodonContentMover.Post} object was 
   *   reblogged, and saves the change to file if persistance has not been paused.
   *   <br /><br />
   *   This field may later be used to determine whether or not to repost this {@link io.github.mastodonContentMover.Post} 
   *   object, based on a threshold specified when the tool is run. (TODO)
   *   <br /><br />
   *   @param rc the number of times the Mastodon status represented by this {@link io.github.mastodonContentMover.Post} 
   *             object had been reblogged when it was saved to the archive
   *   <br /><br />
   *   @throws JAXBException (TODO: add more info here on when this happens)
   *   @throws IOException (TODO: add more info here on when this happens)
   *   <br /><br />
   *   @see <a href="https://docs.joinmastodon.org/entities/Status/#reblogs_count">{@code reblogs_count} for the Status object in the Mastodon API documentation</a>
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected synchronized void setReblogsCount(long rc) throws JAXBException, IOException
   {
      this.reblogsCount = rc;

      if (saveOnEveryChange) {
         this.writeToFile();
      }
   }

   /**
   *   Obtains as a primitive {@code long} the number of times the Mastodon status 
   *   represented by this {@link io.github.mastodonContentMover.Post} object was 
   *   reblogged.
   *   <br /><br />
   *   This may later be used to determine whether or not to repost this {@link io.github.mastodonContentMover.Post} 
   *   object, based on a threshold specified when the tool is run. (TODO)
   *   <br /><br />
   *   @return the number of times the Mastodon status represented by this {@link io.github.mastodonContentMover.Post} 
   *            object had been reblogged when it was saved to the archive
   *   <br /><br />
   *   @see <a href="https://docs.joinmastodon.org/entities/Status/#reblogs_count">{@code reblogs_count} for the Status object in the Mastodon API documentation</a>
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected long getReblogsCount()
   {
     return this.reblogsCount;
   }

   /**
   *   Stores this {@link io.github.mastodonContentMover.Post} object to the filesystem
   *   as an XML file using JAXB persistence. 
   *   <br /><br />
   *   @throws JAXBException (TODO: add more info here on when this happens)
   *   @throws IOException (TODO: add more info here on when this happens)
   *   <br /><br />
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   private synchronized void writeToFile() throws JAXBException, IOException
   {
      JAXBContext jaxbContext = JAXBContext.newInstance(Post.class);
      Marshaller jaxbMarshaller = jaxbContext.createMarshaller();
      jaxbMarshaller.setProperty(Marshaller.JAXB_ENCODING, JAXB_ENCODING);   // This is specified so the text can be edited manually in the XML file if needed, and so emojis and similar characters display correctly if that is done
      jaxbMarshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
   
      //Marshal to console - only left here for extreme debugging
      if (false) {  
         System.out.println(Mover.getDebugPrefix() + "Writing Post to file: \n");
         jaxbMarshaller.marshal(this, System.out);
      }
   
      //Marshal to file
      jaxbMarshaller.marshal(this, new File(filename));
   }

   /**
   *   Saves media from a URL and attaches it to this {@link io.github.mastodonContentMover.Post} 
   *   object, together with its thumbnail (when it is a video) and other metadata used
   *   by Mastodon, as a {@link io.github.mastodonContentMover.MediaFile} object.
   *   <br /><br />
   *   @param u the URL for the media file
   *   @param mmt the media type as specified by/for Mastodon
   *   @param tu the URL of the thumbnail for this media
   *   @param at the "alt text" or description of this media
   *   @param x the x-coordinate of the Mastodon focal point for this media
   *   @param y the y-coordinate of the Mastodon focal point for this media
   *   <br /><br />
   *   @throws FileNotFoundException (TODO: add more info here on when this happens)
   *   @throws MalformedURLException (TODO: add more info here on when this happens)
   *   @throws IOException (TODO: add more info here on when this happens)
   *   <br /><br />
   *   @see io.github.mastodonContentMover.MediaFile
   *   @see <a href="https://docs.joinmastodon.org/entities/MediaAttachment/">The MediaAttachment object in the Mastodon API documentation</a>
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected void addMedia(String u, String mmt, String tu, String at, float x, float y) throws FileNotFoundException, MalformedURLException, IOException
   {
      if (this.mediaFiles == null) { this.mediaFiles = new ArrayList<MediaFile>(); }

      // First, download the file
      String[] f = u.split("\\.");
      String fileExtension = f[(f.length - 1)];
      String filename = (this.mediaFiles.size() + 1) + "." + fileExtension;
      String filepath = this.directory + File.separator + filename;   // Add one so we count from one, because that's what humans understand
      String mastodonMediaType = mmt;

      if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "Saving media from " + u + " to " + filepath + " \n");  }

      // Let's use NIO for the performance advantages. Maybe I should use NIO in other places to - should look into it (TODO)
      ReadableByteChannel in = Channels.newChannel(new URL(u).openStream());
      FileChannel out = new FileOutputStream(filepath).getChannel();
      out.transferFrom(in, 0, Long.MAX_VALUE);

      // Now, do almost the exact same thing again with the thumbnail
      String thumbnailFilepath = null;
      String thumbnailFilename = null;
      if (tu != null)
      {
         f = tu.split("\\.");
         fileExtension = f[(f.length - 1)];
         thumbnailFilename = (this.mediaFiles.size() + 1) + MediaFile.getThumbnailFileSuffix() + "." + fileExtension;
         thumbnailFilepath = this.directory + File.separator + thumbnailFilename;  

         if (Mover.showDebug()) {  System.out.println(Mover.getDebugPrefix() + "Saving thumbnail from " + tu + " to " + thumbnailFilepath + " \n");  }

         in = Channels.newChannel(new URL(tu).openStream());
         out = new FileOutputStream(thumbnailFilepath).getChannel();
         out.transferFrom(in, 0, Long.MAX_VALUE);
      }

      this.mediaFiles.add(new MediaFile(filename, this.directory, thumbnailFilename, mastodonMediaType, at, x, y));
   }


   /**
   *   Indicates whether this {@link io.github.mastodonContentMover.Post} object has any
   *   media attachments.
   *   <br /><br />
   *   @return {@code true} if this {@link io.github.mastodonContentMover.Post} object
   *            has any media attachments, or {@code false} otherwise
   *   <br /><br />
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected boolean hasMedia()
   {
      return ((this.mediaFiles != null) && (this.mediaFiles.size() > 0));
   }

   /**
   *   Obtains media attached to this {@link io.github.mastodonContentMover.Post} object, 
   *   as a {@link java.util.List} of {@link io.github.mastodonContentMover.MediaFile} objects.
   *   <br /><br />
   *   A read-only, deep-cloned version *should* be returned here; right now the objects
   *   returned are the actual data objects held within this {@link io.github.mastodonContentMover.Post} 
   *   object, when there is no need to modify them programmatically. This should be 
   *   fixed *asap* as inadvertent changes to the data within could then be persisted and
   *   the archive may be damaged (TODO: make MediaFile cloneable then do a deep copy of
   *   the array list here)
   *   <br /><br />
   *   @return a {@link java.util.List} of {@link io.github.mastodonContentMover.MediaFile}
   *            objects representing the media that attached to the status represented by
   *            this {@link io.github.mastodonContentMover.Post} object.
   *   <br /><br />
   *   @see io.github.mastodonContentMover.MediaFile
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected List<MediaFile> getMedia()  
   {
      return this.mediaFiles;
   }


   /**
   *   Determines whether this {@link io.github.mastodonContentMover.Post} object was created (so far as the tool is aware)
   *   after the given date and time, specified as an ISO 8601 compliant {@link java.lang.String}.
   *   <br /><br />
   *   This relies on the archiveId of the post, which in turn is based on the creation
   *   date of the Mastodon post that was first downloaded and from which this
   *   {@link io.github.mastodonContentMover.Post} object, in this archive, was first created. 
   *   <br /><br />
   *   Handling of dates and their conversion into archiveIds is done by {@link io.github.mastodonContentMover.PostArchive}
   *   <br /><br />
   *   @param isoDateTime an ISO 8601 compliant {@link java.lang.String}
   *   @return true if this {@link io.github.mastodonContentMover.Post} was created after
   *           the specified date and time, or false otherwise
   *   <br /><br />
   *   @see io.github.mastodonContentMover.Post#getArchiveId()
   *   @see io.github.mastodonContentMover.PostArchive#postIsAfterDateTime(java.lang.String, java.lang.String)
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected boolean isAfter(String isoDateTime) 
   {
      return this.postArchive.postIsAfterDateTime(this.archiveId, isoDateTime);
   }

   /**
   *   Determines whether this {@link io.github.mastodonContentMover.Post} object was created (so far as the tool is aware)
   *   before the given date and time, specified as an ISO 8601 compliant {@link java.lang.String}.
   *   <br /><br />
   *   This relies on the archiveId of the post, which in turn is based on the creation
   *   date of the Mastodon post that was first downloaded and from which this
   *   {@link io.github.mastodonContentMover.Post} object, in this archive, was first created.
   *   <br /><br />
   *   Handling of dates and their conversion into archiveIds is done by {@link io.github.mastodonContentMover.PostArchive}
   *   <br /><br />
   *   @param isoDateTime an ISO 8601 compliant {@link java.lang.String}
   *   @return true if this {@link io.github.mastodonContentMover.Post} was created before
   *           the specified date and time, or false otherwise
   *   <br /><br />
   *   @see io.github.mastodonContentMover.Post#getArchiveId()
   *   @see io.github.mastodonContentMover.PostArchive#postIsBeforeDateTime(String, String)
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected boolean isBefore(String isoDateTime) 
   {
      return this.postArchive.postIsBeforeDateTime(this.archiveId, isoDateTime);
   }

}