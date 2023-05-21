/**
*   Saves statuses from a user account on a Mastodon instance to an XML-based archive
*   on the local filesystem, and posts them as new statuses from that archive to a user 
*   account on a Mastodon instance, to preserve and migrate content in posted statuses 
*   when users migrate between Mastodon instances.
*   <br /><br />
*   The tool is run from {@link io.github.mastodonContentMover.Mover}, which connects to
*   the specified Mastodon instance using {@link io.github.mastodonContentMover.Authenticator}
*   and then saves {@link io.github.mastodonContentMover.Post} objects to or posts them 
*   from a {@link io.github.mastodonContentMover.PostArchive}. Media attachments for {@link io.github.mastodonContentMover.Post} 
*   objects are held in {@link io.github.mastodonContentMover.MediaFile} objects.
*   <br /><br />
*   Access credentials are stored by {@link io.github.mastodonContentMover.Authenticator}
*   using {@link io.github.mastodonContentMover.UserCredentialStore} and {@link io.github.mastodonContentMover.ClientCredentialStore},
*   which in turn uses {@link io.github.mastodonContentMover.ClientCredentialSet} to hold
*   client key/secret pairs. OAuth authentication managed within {@link io.github.mastodonContentMover.Authenticator}
*   is aided by {@link io.github.mastodonContentMover.OAuthListener}.
*   <br /><br />
*   XML persistance uses JAXB, with the text of users' statuses saved in a format that
*   can be inspected and edited outside of the tool, using a basic text editor, after 
*   saving and before posting. Media files and associated metadata saved from a Mastodon 
*   instance can also be adjusted if needed. For example, the version of a video or image
*   downloaded from a Mastodon instance could be replaced with a higher-resolution or
*   quality version within the archive, before reposting, to ensure there is no loss due
*   to encoding settings on the previous instance. Saved statuses are organised together
*   with their associated media in folders according to their creation date and time.
*   <br /><br />
*   User and client credentials are also saved in XML format, and can be inspected and
*   edited if needed. Please ensure you run the tool from a working directory that is
*   secure (not on a public computer) in order to keep your access token private, or
*   delete userCredentials.xml from the data directory after use. Failure to do this may
*   allow others to access your Mastodon account.
*   <br /><br />
*/
package io.github.mastodonContentMover;