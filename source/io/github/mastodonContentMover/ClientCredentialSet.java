package io.github.mastodonContentMover;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;

/**
*   Holds in an XML-persistable object a {@code client-key} and {@code client-secret} 
*   pair that grants application-level API credentials for the tool with a Mastodon
*   instance.
*   <br /><br />
*   @see io.github.mastodonContentMover.ClientCredentialStore
*   @author Tokyo Outsider
*   @since 0.01.00
*/
@XmlRootElement(name = "clientCredentialSet")
@XmlAccessorType (XmlAccessType.FIELD)
public class ClientCredentialSet {

   private String clientKey;
   private String clientSecret;

   private ClientCredentialSet() {
   // let's not
   }   

   /**
   *   Creates a {@link io.github.mastodonContentMover.ClientCredentialSet} object
   *   containing the specified {@code client-key} and {@code client-secret} pair. 
   *   <br /><br />
   *   @param clientKey a {@code client-key} for the tool on a Mastodon instance
   *   @param clientSecret a {@code client-secret} for the tool on a Mastodon instance
   *   <br /><br />
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   public ClientCredentialSet(String clientKey, String clientSecret) {
      this.clientKey = clientKey;
      this.clientSecret = clientSecret;
   }   

   /**
   *   Retrieves the {@code client-key} that, together with a {@code client-secret},
   *   grants application-level API access token for this tool on a Mastodon instance.
   *   <br /><br />
   *   @return a {@code client-key} for the tool on a Mastodon instance
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   public String getClientKey() {
      return this.clientKey;
   }

   /**
   *   Retrieves the {@code client-secret} that, together with a {@code client-key},
   *   grants application-level API access token for this tool on a Mastodon instance.
   *   <br /><br />
   *   @return a {@code client-secret} for the tool on a Mastodon instance
   *   @since 0.01.00
   *   @author Tokyo Outsider
   */
   public String getClientSecret() {
      return this.clientSecret;
   }
  
}