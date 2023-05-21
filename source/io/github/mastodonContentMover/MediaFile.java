package io.github.mastodonContentMover;

import java.util.regex.Pattern;
import java.io.File;
import java.io.IOException;

//   JAXB for persistence
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlTransient;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;


/**
*   Holds in an XML-persistable object the metadata associated with a media file attached
*   to a {@link io.github.mastodonContentMover.Post} object.
*
*   Because this class uses JAXB and instances of it are held within {@link io.github.mastodonContentMover.Post}
*   objects, persistance is managed by the associated {@link io.github.mastodonContentMover.Post}
*   object (instances of this class are saved when the {@link io.github.mastodonContentMover.Post}
*   object that contains them are saved), so no marshalling or unmarshalling code is needed here.
*
*   @author Tokyo Outsider
*   @since 0.01.00
*/
@XmlRootElement(name = "mediaData")
@XmlAccessorType(XmlAccessType.FIELD)
public class MediaFile {

   private String filename;
   private String mimeType;
   private String mastodonMediaType;
   private String thumbnailFilename;
   private String altText;
   private float focalPointX;
   private float focalPointY;

   @XmlTransient
   private String directory;   // This is transient as it may change due to manual manipulation of the archive data before the tool is run, so is determined at run time instead.

   // Note, no @XmlTransient tag needed for static constants
   private static final String FILE_EXTENSION_JPEG = "jpeg";
   private static final String FILE_EXTENSION_JPG = "jpg";
   private static final String FILE_EXTENSION_MPFOUR = "mp4";
   private static final String MIME_TYPE_IMAGE_JPEG = "image/jpeg";
   private static final String MIME_TYPE_VIDEO_MPFOUR = "video/mp4";
   private static final String MIME_TYPE_AUDIO_MPTHREE = "audio/mpeg";
   private static final String MIME_TYPE_GENERIC_BINARY = "application/octet-stream";

   private static final String THUMBNAIL_FILE_SUFFIX = "_thumbnail";

   private MediaFile() {
// Don't
   }   

   /**
   *   Creates a {@link io.github.mastodonContentMover.MediaFile} object with the 
   *   filename specified.
   *   <br /><br />
   *   @param fn the filename for the media file to be associated with this {@link io.github.mastodonContentMover.MediaFile}
   *             object 
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected MediaFile(String fn) {
      this.filename = fn;
   }  

   /**
   *   Creates a {@link io.github.mastodonContentMover.MediaFile} object with the 
   *   filename, directory, Mastodon media type, thumbnail filename, alt text
   *   (description) and focal point coordinates (x and y) specified.
   *   <br /><br />
   *   MIME type is determined based on the extension of the media file, so it can be
   *   used when reuploading the file to Mastodon to attach to a reposted status.
   *   <br /><br />
   *   @param fn the filename for the media file to be associated with this {@link io.github.mastodonContentMover.MediaFile}
   *             object 
   *   @param d the directory where the media file is stored within the local file system
   *   @param tfn the filename of a thumbnail for the media file
   *   @param mm the Mastodon media type 
   *   @param at the alt text or description of the content in this media file
   *   @param x the x coordinate for the focal point for this media file
   *   @param y the y coordinate for the focal point for this media file
   *   <br /><br />
   *   @see <a href="https://docs.joinmastodon.org/entities/MediaAttachment/#type">{@code type} for the MediaAttachment object in the Mastodon API documentation</a>
   *   @see <a href="https://docs.joinmastodon.org/api/guidelines/#focal-points">Media focal points in the Mastodon API documentation</a>
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected MediaFile(String fn, String d, String tfn, String mm, String at, float x, float y) {
      this.filename = fn;
      this.directory = d;

      // Figure out MIME type
      String[] f = fn.split("\\.");
      String fileExtension = f[(f.length - 1)];
      if (Mover.showDebug()) {  System.out.println("New media with extension: " + fileExtension + " \n");  }

      switch (fileExtension.toLowerCase()) {
         case FILE_EXTENSION_JPEG:
            this.mimeType = MIME_TYPE_IMAGE_JPEG;
         break;
         case FILE_EXTENSION_JPG:
            this.mimeType = MIME_TYPE_IMAGE_JPEG;
         break;
         case FILE_EXTENSION_MPFOUR:
            this.mimeType = MIME_TYPE_VIDEO_MPFOUR;
         break;
         default:
            this.mimeType = MIME_TYPE_GENERIC_BINARY;
      }   // end switch -- 
      // Also - need to add the weird GIFV format

      this.thumbnailFilename = tfn;
      this.mastodonMediaType = mm;
      this.altText = at;
      this.focalPointX = x;
      this.focalPointY = y;

   }  

   /**
   *   Stores the directory where the media file associated with this {@link io.github.mastodonContentMover.MediaFile}
   *   object is stored on the local file system.
   *   <br /><br />
   *   @param d the directory where the media file is stored
   *   <br /><br />
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected void setDirectory(String d) {   
      this.directory = d;
   }

   /**
   *   Retrieves the path on the local file system for the media file associated
   *   with this {@link io.github.mastodonContentMover.MediaFile} object.
   *   <br /><br />
   *   @return the path on the local file system for the media file
   *   <br /><br />
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected String getFilepath() {

      String filepath = null;
      if (this.directory != null) {
         filepath = this.directory + File.separator + this.filename;
      }
      else {                    // This basically shouldn't happen
         filepath = this.filename;
      }
      return filepath.replaceAll(Pattern.quote(File.separator), "/"); 
   }

   /**
   *   Retrieves the path on the local file system for the thumbnail for the media file
   *   associated with this {@link io.github.mastodonContentMover.MediaFile} object.
   *   <br /><br />
   *   @return the path on the local file system for the thumbnail file
   *   <br /><br />
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected String getThumbnailFilepath() {

      String tfp = null;
      if (this.directory != null) {
         tfp = this.directory + File.separator + this.thumbnailFilename;
      }
      else {                    // This basically shouldn't happen
         tfp = this.thumbnailFilename;
      }
      return tfp.replaceAll(Pattern.quote(File.separator), "/"); 
   }

   /**
   *   Obtain the suffix that should be attached to the name of a media file (without
   *   the file extension) when naming a thumbnail for that media file.
   *   <br /><br />
   *   @return the suffix
   *   <br /><br />
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected static String getThumbnailFileSuffix() {
      return THUMBNAIL_FILE_SUFFIX;
   }

   /**
   *   Retrieves the MIME type of the media file associated with this {@link io.github.mastodonContentMover.MediaFile}
   *   object, used when reuploading the file to Mastodon to attach to a reposted status.
   *   <br /><br />
   *   @return the MIME type of the media file
   *   <br /><br />
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected String getMimeType() {
      return this.mimeType;
   }

   /**
   *   Retrieves the Mastodon media type for the media file associated with this {@link io.github.mastodonContentMover.MediaFile},
   *   corresponding to the {@code description} field within Mastodon.
   *   object.
   *   <br /><br />
   *   @return the Mastodon media type for the media file 
   *   <br /><br />
   *   @see <a href="https://docs.joinmastodon.org/entities/MediaAttachment/#type">{@code type} for the MediaAttachment object in the Mastodon API documentation</a>
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected String getMastodonMediaType() {
      return this.mastodonMediaType;
   }

   /**
   *   Stores the value of the Mastodon {@code description} field, which is an alt text
   *   or description, for the media file associated with this {@link io.github.mastodonContentMover.MediaFile}
   *   object.
   *   <br /><br />
   *   @param at the alt text or description of the content in the media file
   *   <br /><br />
   *   @see <a href="https://docs.joinmastodon.org/entities/MediaAttachment/#description">{@code description} for the MediaAttachment object in the Mastodon API documentation</a>
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected void setAltText(String at) {
      this.altText = at;
   }

   /**
   *   Retrieves the alt text or description of the content in the media file associated
   *   with this {@link io.github.mastodonContentMover.MediaFile}, corresponding to the
   *   {@code description} field within Mastodon.
   *   object.
   *   <br /><br />
   *   @return the alt text or description of the content in the media file
   *   <br /><br />
   *   @see <a href="https://docs.joinmastodon.org/entities/Status/#spoiler_text">{@code spoiler_text} for the Status object in the Mastodon API documentation</a>
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected String getAltText() {
      return this.altText;
   }

   /**
   *   Stores the x and y coordinates of the focal point for the media file associated 
   *   with this {@link io.github.mastodonContentMover.MediaFile} object, which allow
   *   Mastodon to preview a limited area of an image according to the user's preferences
   *   when not all of it is visible in the user interface.
   *   <br /><br />
   *   @param x the x coordinate for the focal point for this media file
   *   @param y the y coordinate for the focal point for this media file
   *   <br /><br />
   *   @see <a href="https://docs.joinmastodon.org/api/guidelines/#focal-points">Media focal points in the Mastodon API documentation</a>
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected void setFocalPoint(float x, float y) {
      this.focalPointX = x;
      this.focalPointY = y;
   }

   /**
   *   Retrieves x coordinate of the focal point for the media file associated 
   *   with this {@link io.github.mastodonContentMover.MediaFile} object.
   *   <br /><br />
   *   @return the x coordinate for the focal point for this media file
   *   <br /><br />
   *   @see <a href="https://docs.joinmastodon.org/api/guidelines/#focal-points">Media focal points in the Mastodon API documentation</a>
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected float getFocalPointX() {
      return this.focalPointX;
   }

   /**
   *   Retrieves y coordinate of the focal point for the media file associated 
   *   with this {@link io.github.mastodonContentMover.MediaFile} object.
   *   <br /><br />
   *   @return the y coordinate for the focal point for this media file
   *   <br /><br />
   *   @see <a href="https://docs.joinmastodon.org/api/guidelines/#focal-points">Media focal points in the Mastodon API documentation</a>
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   protected float getFocalPointY() {
      return this.focalPointY;
   }



}