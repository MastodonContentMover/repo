package io.github.mastodonContentMover;

import java.util.HashMap;
import java.io.File;
import java.io.IOException;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import jakarta.xml.bind.annotation.XmlList;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlTransient;
import jakarta.xml.bind.annotation.XmlSeeAlso;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;

/**
*   Holds in an XML-persistable object the user-level API access tokens for user accounts
*   on respective Mastodon instances.
*   <br /><br />
*   Uses the singleton model, as only one {@link io.github.mastodonContentMover.UserCredentialStore}
*   object is needed to hold all credentials and only one should be instantiated at a 
*   time. Synchronized methods will be synchronized on the singleton object (because there 
*   is only ever one).
*   <br /><br />
*   TODO: Add a parameter that allows the tool to run without persisting any tokens (for
*   user or client) for use in low-security environments.
*   <br /><br />
*   @author Tokyo Outsider
*   @since 0.01.00
*/
@XmlRootElement(name = "userCredentialStore")
@XmlAccessorType (XmlAccessType.FIELD)
public class UserCredentialStore {

   private static final String FILENAME = Mover.getDataDirectory() + File.separator + "userCredentials.xml";

   @XmlTransient
   private static UserCredentialStore singletonInstance = null;

   @XmlElementWrapper
   private static HashMap<String, String> store = null;

   private UserCredentialStore() {
      store = new HashMap();
   } 

   /**
   *   Obtains a reference to the currently instantiated {@link io.github.mastodonContentMover.UserCredentialStore}
   *   singleton object, or instantiates one if that has not already been done.
   *   <br /><br />
   *   @return a reference to an {@link io.github.mastodonContentMover.UserCredentialStore} object
   *   <br /><br />
   *   @throws JAXBException (TODO: add more info here on when this happens)
   *   @throws IOException (TODO: add more info here on when this happens)
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   public static synchronized UserCredentialStore getSingletonInstance() throws JAXBException, IOException {
      if (singletonInstance == null) {
         try {
            singletonInstance = UserCredentialStore.readFromFile();
         } catch (IllegalArgumentException iae) {                        // Thrown when the xml file does not exist
         singletonInstance = new UserCredentialStore();
         }
      }
      return singletonInstance;
   }

   /**
   *   Adds a user-level API access token for a specified user account on a Mastodon 
   *   instance specified by hostname or address to the object data store within this
   *   {@link io.github.mastodonContentMover.UserCredentialStore} object and saves its
   *   state to file in an XML format using JAXB.
   *   <br /><br />
   *   The user account and instance data is stored by one-way concatenation of the 
   *   instance address and user account name, separated with an underscore, which is
   *   then used as a key that maps to the access token.
   *   <br /><br />
   *   @param instance the hostname or address of the Mastodon instance
   *   @param username the username for the account on the Mastodon instance
   *   @param accessToken a user-level API access token 
   *   <br /><br />
   *   @throws JAXBException (TODO: add more info here on when this happens)
   *   @throws IOException (TODO: add more info here on when this happens)
   *   <br /><br />
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   public synchronized void addCredentials(String instance, String username, String accessToken) throws JAXBException, IOException {
     store.put(getLookupKey(instance, username), accessToken);
     writeToFile();
   }

   /**
   *   Retrieves a user-level API access token for a specified user account on a Mastodon 
   *   instance specified by hostname or address from the object data store within this
   *   {@link io.github.mastodonContentMover.UserCredentialStore} instance.
   *   <br /><br />
   *   @param instance the hostname or address of the Mastodon instance
   *   @param username the username for the account on the Mastodon instance
   *   <br /><br />
   *   @return a user-level API access token for the specified user account and instance
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   public String getAccessToken(String instance, String username) {
      return store.get(getLookupKey(instance, username));
   }

   private String getLookupKey(String instance, String username) {
      // return (instance.replaceAll("\\s", "").replace('.', '_') + "-" + username.replaceAll("\\s", "").replace('.', '_')); // Apparently PeerTube usernames can have dots.
      return (instance + "_" + username); // There's actually no reason to strip the other characters out, and the underscore is only added for readability
   }

   private static void writeToFile() throws JAXBException, IOException
   {
      if (Mover.checkDataDirectoryExists()) {
         synchronized (singletonInstance) {
            JAXBContext jaxbContext = JAXBContext.newInstance(UserCredentialStore.class);
            Marshaller jaxbMarshaller = jaxbContext.createMarshaller();
 
            jaxbMarshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
   
            //Marshal to console - for extreme debugging, as it exposes user credentials (access tokens) on the command line
            if (false) {  
               System.out.println(Mover.getDebugPrefix() + "Writing UserCredentialStore to file: \n");
               jaxbMarshaller.marshal(singletonInstance, System.out);
            }
   
            //Marshal to file
            jaxbMarshaller.marshal(singletonInstance, new File(FILENAME));
         }
      } else {
         throw new IOException("Could not create data directory or verify that it exists");
      }
   }

   private static UserCredentialStore readFromFile() throws JAXBException, IOException
   { 
      if (Mover.checkDataDirectoryExists()) {
         JAXBContext jaxbContext = JAXBContext.newInstance(UserCredentialStore.class);
         Unmarshaller jaxbUnmarshaller = jaxbContext.createUnmarshaller();
   
         return (UserCredentialStore) jaxbUnmarshaller.unmarshal(new File(FILENAME));
      } else {
         throw new IOException(Mover.getErrorMessagePrefix() + "Could not create data directory or verify that it exists");
      }
   }
}