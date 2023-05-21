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
*   Holds in an XML-persistable object the application-level API credentials for the tool
*   with respective Mastodon instances, using {@link io.github.mastodonContentMover.ClientCredentialSet} 
*   objects to store individual {@code client-key} and {@code client-secret} pairs.
*   <br /><br />
*   Uses the singleton model, as only one {@link io.github.mastodonContentMover.ClientCredentialStore}
*   object is needed to hold all credentials and only one should be instantiated at a 
*   time. Synchronized methods will be synchronized on the singleton object (because there 
*   is only ever one).
*   <br /><br />
*   TODO: Add a parameter that allows the tool to run without persisting any tokens (for
*   user or client) for use in low-security environments.
*   <br /><br />
*   @see io.github.mastodonContentMover.ClientCredentialSet 
*   @author Tokyo Outsider
*   @since 0.01.00
*/
@XmlRootElement(name = "clientCredentialStore")
@XmlAccessorType (XmlAccessType.FIELD)
public class ClientCredentialStore {

   private static final String FILENAME = Mover.getDataDirectory() + File.separator + "clientCredentials.xml";

   @XmlTransient
   private static ClientCredentialStore singletonInstance = null;

   @XmlElementWrapper
   private static HashMap<String, ClientCredentialSet> store = null;

   private ClientCredentialStore() {
      store = new HashMap();
   } 

   /**
   *   Obtains a reference to the currently instantiated {@link io.github.mastodonContentMover.ClientCredentialStore}
   *   singleton object, or instantiates one if that has not already been done.
   *   <br /><br />
   *   @return a reference to an {@link io.github.mastodonContentMover.ClientCredentialStore} object
   *   <br /><br />
   *   @throws JAXBException (TODO: add more info here on when this happens)
   *   @throws IOException (TODO: add more info here on when this happens)
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   public static synchronized ClientCredentialStore getSingletonInstance() throws JAXBException, IOException {
      if (singletonInstance == null) {
         try {
            singletonInstance = ClientCredentialStore.readFromFile();
         } catch (IllegalArgumentException iae) {                        // Thrown when the xml file does not exist
         singletonInstance = new ClientCredentialStore();
         }
      }
      return singletonInstance;
   }

   /**
   *   Adds a {@code client-key} and {@code client-secret} pair that grant 
   *   application-level API read and write access for the tool on a Mastodon instance,
   *   specified by hostname or address, to the data store within this {@link io.github.mastodonContentMover.ClientCredentialStore}
   *   object and saves its state to file in an XML format using JAXB.
   *   <br /><br />
   *   The {@code client-key} and {@code client-secret} pair are stored using a {@link io.github.mastodonContentMover.ClientCredentialSet}
   *   object.
   *   <br /><br />
   *   @param instance the hostname or address of the Mastodon instance
   *   @param clientKey the {@code client-key} for the tool on the Mastodon instance
   *   @param clientSecret the {@code client-secret} for the tool on the Mastodon instance
   *   <br /><br />
   *   @throws JAXBException (TODO: add more info here on when this happens)
   *   @throws IOException (TODO: add more info here on when this happens)
   *   <br /><br />
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   public synchronized void addCredentials(String instance, String clientKey, String clientSecret) throws JAXBException, IOException {
      ClientCredentialSet s = new ClientCredentialSet(clientKey, clientSecret);
      store.put(instance, s);
      writeToFile();
   }

   /**
   *   Retrieves a {@code client-key} and {@code client-secret} pair that grant 
   *   application-level API read and write access for the tool on a Mastodon instance,
   *   specified by hostname or address, contained within a {@link io.github.mastodonContentMover.ClientCredentialSet}
   *   object, from the data store within this {@link io.github.mastodonContentMover.ClientCredentialStore}
   *   object.
   *   <br /><br />
   *   @param instance the hostname or address of the Mastodon instance
   *   <br /><br />
   *   @return a {@link io.github.mastodonContentMover.ClientCredentialSet} object 
   *           containing the {@code client-key} and {@code client-secret} pair for the 
   *           tool on the specified Mastodon instance
   *   <br /><br />
   *   @throws JAXBException (TODO: add more info here on when this happens)
   *   @throws IOException (TODO: add more info here on when this happens)
   *   <br /><br />
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   public ClientCredentialSet getCredentials(String instance) throws JAXBException, IOException {
      return store.get(instance);
   }

   private static void writeToFile() throws JAXBException, IOException
   {
      if (Mover.checkDataDirectoryExists()) {
         synchronized (singletonInstance) {
            JAXBContext jaxbContext = JAXBContext.newInstance(ClientCredentialStore.class);
            Marshaller jaxbMarshaller = jaxbContext.createMarshaller();
 
            jaxbMarshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
   
            //Marshal to console
            if (Mover.showDebug()) {  
               System.out.println(Mover.getDebugPrefix() + "Writing ClientCredentialStore to file: \n");
               jaxbMarshaller.marshal(singletonInstance, System.out);
            }
   
            //Marshal to file
            jaxbMarshaller.marshal(singletonInstance, new File(FILENAME));
         }
      } else {
         throw new IOException(Mover.getErrorMessagePrefix() + "Could not create data directory or verify that it exists");
      }
   }

   private static ClientCredentialStore readFromFile() throws JAXBException, IOException
   { 
      if (Mover.checkDataDirectoryExists()) {
         JAXBContext jaxbContext = JAXBContext.newInstance(ClientCredentialStore.class);
         Unmarshaller jaxbUnmarshaller = jaxbContext.createUnmarshaller();
   
         return (ClientCredentialStore) jaxbUnmarshaller.unmarshal(new File(FILENAME));
      } else {
         throw new IOException(Mover.getErrorMessagePrefix() + "Could not create data directory or verify that it exists");
      }
   }
}