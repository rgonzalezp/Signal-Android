package org.thoughtcrime.securesms.jobs;

import android.content.Context;
import android.util.Log;
import android.util.Pair;

import org.thoughtcrime.securesms.crypto.MasterCipher;
import org.thoughtcrime.securesms.crypto.MasterSecret;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.PartDatabase;
import org.thoughtcrime.securesms.dependencies.InjectableType;
import org.thoughtcrime.securesms.events.TransferProgressEvent;
import org.thoughtcrime.securesms.jobs.requirements.MasterSecretRequirement;
import org.thoughtcrime.securesms.util.Base64;
import org.thoughtcrime.securesms.util.ConnectivityUtil;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.util.Util;
import org.whispersystems.jobqueue.JobParameters;
import org.whispersystems.jobqueue.requirements.NetworkRequirement;
import org.whispersystems.libaxolotl.InvalidMessageException;
import org.whispersystems.textsecure.api.TextSecureMessageReceiver;
import org.whispersystems.textsecure.api.messages.TextSecureAttachmentPointer;
import org.whispersystems.textsecure.api.push.exceptions.NonSuccessfulResponseCodeException;
import org.whispersystems.textsecure.api.push.exceptions.PushNetworkException;
import org.whispersystems.textsecure.api.util.TransferObserver;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import javax.inject.Inject;

import de.greenrobot.event.EventBus;
import ws.com.google.android.mms.MmsException;
import ws.com.google.android.mms.pdu.PduPart;

public class AttachmentDownloadJob extends MasterSecretJob implements InjectableType {

  private static final String TAG = AttachmentDownloadJob.class.getSimpleName();

  @Inject transient TextSecureMessageReceiver messageReceiver;

  private final long messageId;

  public AttachmentDownloadJob(Context context, long messageId) {
    super(context, JobParameters.newBuilder()
                                .withRequirement(new MasterSecretRequirement(context))
                                .withRequirement(new NetworkRequirement(context))
                                .withPersistence()
                                .create());

    this.messageId = messageId;
  }

  @Override
  public void onAdded() {}

  @Override
  public void onRun(MasterSecret masterSecret) throws IOException {
    PartDatabase database = DatabaseFactory.getPartDatabase(context);

    Log.w(TAG, "Downloading push parts for: " + messageId);

    List<Pair<Long, PduPart>> parts = database.getParts(masterSecret, messageId, false);

    for (Pair<Long, PduPart> partPair : parts) {
      retrievePart(masterSecret, partPair.second, messageId, partPair.first);
      Log.w(TAG, "Got part: " + partPair.first);
    }
  }

  @Override
  public void onCanceled(MasterSecret masterSecret) {
    if (masterSecret == null) {
      Log.w(TAG, "master secret was null onCanceled(), so couldn't mark parts as failed.");
      return;
    }
    PartDatabase              database = DatabaseFactory.getPartDatabase(context);
    List<Pair<Long, PduPart>> parts    = database.getParts(masterSecret, messageId, false);

    for (Pair<Long, PduPart> partPair : parts) {
      markFailed(messageId, partPair.second, partPair.first);
    }
  }

  @Override
  public boolean onShouldRetryThrowable(Exception exception) {
    if (exception instanceof PushNetworkException) return true;

    return false;
  }

  private boolean shouldAutoDownload(PduPart part) {
    final boolean wifi    = ConnectivityUtil.isWifiConnected(context);
    final boolean roaming = ConnectivityUtil.isRoaming(context);

    return ((part.isImage() &&
              ((wifi    && TextSecurePreferences.isWifiImageAutoDownloadAllowed(context))     ||
               (roaming && TextSecurePreferences.isRoamingImageAutoDownloadAllowed(context))) ||
               (TextSecurePreferences.isDataImageAutoDownloadAllowed(context))) ||
            (part.isAudio() &&
              ((wifi    && TextSecurePreferences.isWifiAudioAutoDownloadAllowed(context))     ||
               (roaming && TextSecurePreferences.isRoamingAudioAutoDownloadAllowed(context))) ||
               (TextSecurePreferences.isDataAudioAutoDownloadAllowed(context))) ||
            (part.isVideo() &&
              ((wifi    && TextSecurePreferences.isWifiVideoAutoDownloadAllowed(context))     ||
               (roaming && TextSecurePreferences.isRoamingVideoAutoDownloadAllowed(context))) ||
               (TextSecurePreferences.isDataVideoAutoDownloadAllowed(context))));
  }

  private void retrievePart(MasterSecret masterSecret, PduPart part, final long messageId, long partId)
      throws IOException
  {
    Log.w(TAG, "retrieving part of type " + Util.toIsoString(part.getContentType()));

    if (!shouldAutoDownload(part)) {
      markPendingApproval(messageId, part, partId);
      return;
    }
    PartDatabase database       = DatabaseFactory.getPartDatabase(context);
    File         attachmentFile = null;

    try {
      attachmentFile = createTempFile();

      TransferObserver observer = new TransferObserver() {
        @Override
        public void onUpdate(long current, long total) {
          Log.w(TAG, "got attachment recv event: " + (((float)current / (float)total) * 100) + "%");
          EventBus.getDefault().postSticky(new TransferProgressEvent(messageId, current, total));
        }
      };

      TextSecureAttachmentPointer pointer    = createAttachmentPointer(masterSecret, part);
      InputStream                 attachment = messageReceiver.retrieveAttachment(pointer, attachmentFile, observer);

      database.updateDownloadedPart(masterSecret, messageId, partId, part, attachment);
    } catch (InvalidPartException | NonSuccessfulResponseCodeException | InvalidMessageException | MmsException e) {
      Log.w(TAG, e);
      markFailed(messageId, part, partId);
    } finally {
      if (attachmentFile != null)
        attachmentFile.delete();
    }
  }

  private TextSecureAttachmentPointer createAttachmentPointer(MasterSecret masterSecret, PduPart part)
      throws InvalidPartException
  {
    try {
      MasterCipher masterCipher = new MasterCipher(masterSecret);
      long         id           = Long.parseLong(Util.toIsoString(part.getContentLocation()));
      byte[]       key          = masterCipher.decryptBytes(Base64.decode(Util.toIsoString(part.getContentDisposition())));
      String       relay        = null;

      if (part.getName() != null) {
        relay = Util.toIsoString(part.getName());
      }

      return new TextSecureAttachmentPointer(id, null, key, relay);
    } catch (InvalidMessageException | IOException e) {
      Log.w(TAG, e);
      throw new InvalidPartException(e);
    }
  }

  private File createTempFile() throws InvalidPartException {
    try {
      File file = File.createTempFile("push-attachment", "tmp");
      file.deleteOnExit();

      return file;
    } catch (IOException e) {
      throw new InvalidPartException(e);
    }
  }

  private void markFailed(long messageId, PduPart part, long partId) {
    try {
      PartDatabase database = DatabaseFactory.getPartDatabase(context);
      database.updateFailedDownloadedPart(messageId, partId, part);
    } catch (MmsException e) {
      Log.w(TAG, e);
    }
  }

  private void markPendingApproval(long messageId, PduPart part, long partId) {
    try {
      PartDatabase database = DatabaseFactory.getPartDatabase(context);
      database.updatePendingApprovalPart(messageId, partId, part);
    } catch (MmsException e) {
      Log.w(TAG, e);
    }

  }

  private class InvalidPartException extends Exception {
    public InvalidPartException(Exception e) {super(e);}
  }
}
