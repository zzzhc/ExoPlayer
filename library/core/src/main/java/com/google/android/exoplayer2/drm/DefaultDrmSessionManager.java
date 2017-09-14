/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2.drm;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.annotation.IntDef;
import android.support.annotation.NonNull;
import android.text.TextUtils;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.drm.DrmInitData.SchemeData;
import com.google.android.exoplayer2.drm.ExoMediaDrm.OnEventListener;
import com.google.android.exoplayer2.extractor.mp4.PsshAtomUtil;
import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.util.Util;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A {@link DrmSessionManager} that supports playbacks using {@link ExoMediaDrm}.
 */
@TargetApi(18)
public class DefaultDrmSessionManager<T extends ExoMediaCrypto> implements DrmSessionManager<T>,
    DefaultDrmSession.EventListener {

  /**
   * Listener of {@link DefaultDrmSessionManager} events.
   */
  public interface EventListener {

    /**
     * Called each time keys are loaded.
     */
    void onDrmKeysLoaded();

    /**
     * Called when a drm error occurs.
     *
     * @param e The corresponding exception.
     */
    void onDrmSessionManagerError(Exception e);

    /**
     * Called each time offline keys are restored.
     */
    void onDrmKeysRestored();

    /**
     * Called each time offline keys are removed.
     */
    void onDrmKeysRemoved();

  }

  /**
   * The key to use when passing CustomData to a PlayReady instance in an optional parameter map.
   */
  public static final String PLAYREADY_CUSTOM_DATA_KEY = "PRCustomData";
  private static final String CENC_SCHEME_MIME_TYPE = "cenc";

  /** Determines the action to be done after a session acquired. */
  @Retention(RetentionPolicy.SOURCE)
  @IntDef({MODE_PLAYBACK, MODE_QUERY, MODE_DOWNLOAD, MODE_RELEASE})
  public @interface Mode {}
  /**
   * Loads and refreshes (if necessary) a license for playback. Supports streaming and offline
   * licenses.
   */
  public static final int MODE_PLAYBACK = 0;
  /**
   * Restores an offline license to allow its status to be queried.
   */
  public static final int MODE_QUERY = 1;
  /** Downloads an offline license or renews an existing one. */
  public static final int MODE_DOWNLOAD = 2;
  /** Releases an existing offline license. */
  public static final int MODE_RELEASE = 3;

  private final Handler eventHandler;
  private final EventListener eventListener;
  private final ExoMediaDrm<T> mediaDrm;
  private final HashMap<String, String> optionalKeyRequestParameters;
  private final MediaDrmCallback callback;
  private final UUID uuid;
  private final boolean multiSession;

  private Looper playbackLooper;
  private int mode;
  private byte[] offlineLicenseKeySetId;

  private final List<DefaultDrmSession<T>> sessions;
  private final AtomicBoolean provisioningInProgress;
  /* package */ MediaDrmHandler mediaDrmHandler;

  /**
   * Instantiates a new instance using the Widevine scheme.
   *
   * @param callback Performs key and provisioning requests.
   * @param optionalKeyRequestParameters An optional map of parameters to pass as the last argument
   *     to {@link ExoMediaDrm#getKeyRequest(byte[], byte[], String, int, HashMap)}. May be null.
   * @param eventHandler A handler to use when delivering events to {@code eventListener}. May be
   *     null if delivery of events is not required.
   * @param eventListener A listener of events. May be null if delivery of events is not required.
   * @throws UnsupportedDrmException If the specified DRM scheme is not supported.
   */
  public static DefaultDrmSessionManager<FrameworkMediaCrypto> newWidevineInstance(
      MediaDrmCallback callback, HashMap<String, String> optionalKeyRequestParameters,
      Handler eventHandler, EventListener eventListener) throws UnsupportedDrmException {
    return newFrameworkInstance(C.WIDEVINE_UUID, callback, optionalKeyRequestParameters,
        eventHandler, eventListener);
  }

  /**
   * Instantiates a new instance using the PlayReady scheme.
   * <p>
   * Note that PlayReady is unsupported by most Android devices, with the exception of Android TV
   * devices, which do provide support.
   *
   * @param callback Performs key and provisioning requests.
   * @param customData Optional custom data to include in requests generated by the instance.
   * @param eventHandler A handler to use when delivering events to {@code eventListener}. May be
   *     null if delivery of events is not required.
   * @param eventListener A listener of events. May be null if delivery of events is not required.
   * @throws UnsupportedDrmException If the specified DRM scheme is not supported.
   */
  public static DefaultDrmSessionManager<FrameworkMediaCrypto> newPlayReadyInstance(
      MediaDrmCallback callback, String customData, Handler eventHandler,
      EventListener eventListener) throws UnsupportedDrmException {
    HashMap<String, String> optionalKeyRequestParameters;
    if (!TextUtils.isEmpty(customData)) {
      optionalKeyRequestParameters = new HashMap<>();
      optionalKeyRequestParameters.put(PLAYREADY_CUSTOM_DATA_KEY, customData);
    } else {
      optionalKeyRequestParameters = null;
    }
    return newFrameworkInstance(C.PLAYREADY_UUID, callback, optionalKeyRequestParameters,
        eventHandler, eventListener);
  }

  /**
   * Instantiates a new instance.
   *
   * @param uuid The UUID of the drm scheme.
   * @param callback Performs key and provisioning requests.
   * @param optionalKeyRequestParameters An optional map of parameters to pass as the last argument
   *     to {@link ExoMediaDrm#getKeyRequest(byte[], byte[], String, int, HashMap)}. May be null.
   * @param eventHandler A handler to use when delivering events to {@code eventListener}. May be
   *     null if delivery of events is not required.
   * @param eventListener A listener of events. May be null if delivery of events is not required.
   * @throws UnsupportedDrmException If the specified DRM scheme is not supported.
   */
  public static DefaultDrmSessionManager<FrameworkMediaCrypto> newFrameworkInstance(
      UUID uuid, MediaDrmCallback callback, HashMap<String, String> optionalKeyRequestParameters,
      Handler eventHandler, EventListener eventListener) throws UnsupportedDrmException {
    return new DefaultDrmSessionManager<>(uuid, FrameworkMediaDrm.newInstance(uuid), callback,
        optionalKeyRequestParameters, eventHandler, eventListener, false);
  }

  /**
   * @param uuid The UUID of the drm scheme.
   * @param mediaDrm An underlying {@link ExoMediaDrm} for use by the manager.
   * @param callback Performs key and provisioning requests.
   * @param optionalKeyRequestParameters An optional map of parameters to pass as the last argument
   *     to {@link ExoMediaDrm#getKeyRequest(byte[], byte[], String, int, HashMap)}. May be null.
   * @param eventHandler A handler to use when delivering events to {@code eventListener}. May be
   *     null if delivery of events is not required.
   * @param eventListener A listener of events. May be null if delivery of events is not required.
   */
  public DefaultDrmSessionManager(UUID uuid, ExoMediaDrm<T> mediaDrm, MediaDrmCallback callback,
      HashMap<String, String> optionalKeyRequestParameters, Handler eventHandler,
      EventListener eventListener) {
    this(uuid, mediaDrm, callback, optionalKeyRequestParameters, eventHandler, eventListener,
        false);
  }

  /**
   * @param uuid The UUID of the drm scheme.
   * @param mediaDrm An underlying {@link ExoMediaDrm} for use by the manager.
   * @param callback Performs key and provisioning requests.
   * @param optionalKeyRequestParameters An optional map of parameters to pass as the last argument
   *     to {@link ExoMediaDrm#getKeyRequest(byte[], byte[], String, int, HashMap)}. May be null.
   * @param eventHandler A handler to use when delivering events to {@code eventListener}. May be
   *     null if delivery of events is not required.
   * @param eventListener A listener of events. May be null if delivery of events is not required.
   * @param multiSession A boolean that specify whether multiple key session support is enabled.
   *     Default is false.
   */
  public DefaultDrmSessionManager(UUID uuid, ExoMediaDrm<T> mediaDrm, MediaDrmCallback callback,
      HashMap<String, String> optionalKeyRequestParameters, Handler eventHandler,
      EventListener eventListener, boolean multiSession) {
    Assertions.checkNotNull(uuid);
    Assertions.checkNotNull(mediaDrm);
    Assertions.checkArgument(!C.COMMON_PSSH_UUID.equals(uuid), "Use C.CLEARKEY_UUID instead");
    this.uuid = uuid;
    this.mediaDrm = mediaDrm;
    this.callback = callback;
    this.optionalKeyRequestParameters = optionalKeyRequestParameters;
    this.eventHandler = eventHandler;
    this.eventListener = eventListener;
    this.multiSession = multiSession;
    mode = MODE_PLAYBACK;
    sessions = new ArrayList<>();
    provisioningInProgress = new AtomicBoolean(false);
    if (multiSession) {
      mediaDrm.setPropertyString("sessionSharing", "enable");
    }
  }

  /**
   * Provides access to {@link ExoMediaDrm#getPropertyString(String)}.
   * <p>
   * This method may be called when the manager is in any state.
   *
   * @param key The key to request.
   * @return The retrieved property.
   */
  public final String getPropertyString(String key) {
    return mediaDrm.getPropertyString(key);
  }

  /**
   * Provides access to {@link ExoMediaDrm#setPropertyString(String, String)}.
   * <p>
   * This method may be called when the manager is in any state.
   *
   * @param key The property to write.
   * @param value The value to write.
   */
  public final void setPropertyString(String key, String value) {
    mediaDrm.setPropertyString(key, value);
  }

  /**
   * Provides access to {@link ExoMediaDrm#getPropertyByteArray(String)}.
   * <p>
   * This method may be called when the manager is in any state.
   *
   * @param key The key to request.
   * @return The retrieved property.
   */
  public final byte[] getPropertyByteArray(String key) {
    return mediaDrm.getPropertyByteArray(key);
  }

  /**
   * Provides access to {@link ExoMediaDrm#setPropertyByteArray(String, byte[])}.
   * <p>
   * This method may be called when the manager is in any state.
   *
   * @param key The property to write.
   * @param value The value to write.
   */
  public final void setPropertyByteArray(String key, byte[] value) {
    mediaDrm.setPropertyByteArray(key, value);
  }

  /**
   * Sets the mode, which determines the role of sessions acquired from the instance. This must be
   * called before {@link #acquireSession(Looper, DrmInitData)} is called.
   *
   * <p>By default, the mode is {@link #MODE_PLAYBACK} and a streaming license is requested when
   * required.
   *
   * <p>{@code mode} must be one of these:
   * <ul>
   * <li>{@link #MODE_PLAYBACK}: If {@code offlineLicenseKeySetId} is null, a streaming license is
   *     requested otherwise the offline license is restored.
   * <li>{@link #MODE_QUERY}: {@code offlineLicenseKeySetId} can not be null. The offline license
   *     is restored.
   * <li>{@link #MODE_DOWNLOAD}: If {@code offlineLicenseKeySetId} is null, an offline license is
   *     requested otherwise the offline license is renewed.
   * <li>{@link #MODE_RELEASE}: {@code offlineLicenseKeySetId} can not be null. The offline license
   *     is released.
   * </ul>
   *
   * @param mode The mode to be set.
   * @param offlineLicenseKeySetId The key set id of the license to be used with the given mode.
   */
  public void setMode(@Mode int mode, byte[] offlineLicenseKeySetId) {
    Assertions.checkState(sessions.isEmpty());
    if (mode == MODE_QUERY || mode == MODE_RELEASE) {
      Assertions.checkNotNull(offlineLicenseKeySetId);
    }
    this.mode = mode;
    this.offlineLicenseKeySetId = offlineLicenseKeySetId;
  }

  // DrmSessionManager implementation.

  @Override
  public boolean canAcquireSession(@NonNull DrmInitData drmInitData) {
    SchemeData schemeData = getSchemeData(drmInitData, uuid);
    if (schemeData == null) {
      // No data for this manager's scheme.
      return false;
    }
    String schemeType = drmInitData.schemeType;
    if (schemeType == null || C.CENC_TYPE_cenc.equals(schemeType)) {
      // If there is no scheme information, assume patternless AES-CTR.
      return true;
    } else if (C.CENC_TYPE_cbc1.equals(schemeType) || C.CENC_TYPE_cbcs.equals(schemeType)
        || C.CENC_TYPE_cens.equals(schemeType)) {
      // AES-CBC and pattern encryption are supported on API 24 onwards.
      return Util.SDK_INT >= 24;
    }
    // Unknown schemes, assume one of them is supported.
    return true;
  }

  @Override
  public DrmSession<T> acquireSession(Looper playbackLooper, DrmInitData drmInitData) {
    Assertions.checkState(this.playbackLooper == null || this.playbackLooper == playbackLooper);
    if (sessions.isEmpty()) {
      this.playbackLooper = playbackLooper;
      mediaDrmHandler = new MediaDrmHandler(playbackLooper);
      mediaDrm.setOnEventListener(new MediaDrmEventListener());
    }

    DefaultDrmSession<T> session = null;
    byte[] initData = null;
    String mimeType = null;

    if (offlineLicenseKeySetId == null) {
      SchemeData data = getSchemeData(drmInitData, uuid);
      if (data == null) {
        if (eventHandler != null && eventListener != null) {
          eventHandler.post(new Runnable() {
            @Override
            public void run() {
              eventListener.onDrmSessionManagerError(new IllegalStateException(
                  "Media does not support uuid: " + uuid));
            }
          });
        }
      } else {
        initData = getSchemeInitData(data, uuid);
        mimeType = getSchemeMimeType(data, uuid);
      }
    }

    for (DefaultDrmSession<T> s : sessions) {
      if (!multiSession || s.canReuse(initData)) {
        session = s;
        break;
      }
    }

    if (session == null) {
      session = new DefaultDrmSession<T>(uuid, mediaDrm, initData, mimeType, mode,
          offlineLicenseKeySetId, optionalKeyRequestParameters, callback, playbackLooper,
          eventHandler, eventListener, provisioningInProgress, this);
      sessions.add(session);
    }
    session.acquire();
    return session;
  }

  @Override
  public void releaseSession(DrmSession<T> session) {
    DefaultDrmSession<T> drmSession = (DefaultDrmSession<T>) session;
    if (drmSession.release()) {
      sessions.remove(drmSession);
    }

    if (sessions.isEmpty()) {
      mediaDrm.setOnEventListener(null);
      mediaDrmHandler.removeCallbacksAndMessages(null);
      mediaDrmHandler = null;
      playbackLooper = null;
    }
  }

  @Override
  public void onProvisionCompleted() {
    for (DefaultDrmSession<T> session : sessions) {
      session.onProvisionCompleted();
    }
  }

  /**
   * Extracts {@link SchemeData} suitable for the given DRM scheme {@link UUID}.
   *
   * @param drmInitData The {@link DrmInitData} from which to extract the {@link SchemeData}.
   * @param uuid The UUID.
   * @return The extracted {@link SchemeData}, or null if no suitable data is present.
   */
  private static SchemeData getSchemeData(DrmInitData drmInitData, UUID uuid) {
    SchemeData schemeData = drmInitData.get(uuid);
    if (schemeData == null && C.CLEARKEY_UUID.equals(uuid)) {
      // If present, the Common PSSH box should be used for ClearKey.
      schemeData = drmInitData.get(C.COMMON_PSSH_UUID);
    }
    return schemeData;
  }

  private static byte[] getSchemeInitData(SchemeData data, UUID uuid) {
    byte[] schemeInitData = data.data;
    if (Util.SDK_INT < 21) {
      // Prior to L the Widevine CDM required data to be extracted from the PSSH atom.
      byte[] psshData = PsshAtomUtil.parseSchemeSpecificData(schemeInitData, uuid);
      if (psshData == null) {
        // Extraction failed. schemeData isn't a Widevine PSSH atom, so leave it unchanged.
      } else {
        schemeInitData = psshData;
      }
    }
    return schemeInitData;
  }

  private static String getSchemeMimeType(SchemeData data, UUID uuid) {
    String schemeMimeType = data.mimeType;
    if (Util.SDK_INT < 26 && C.CLEARKEY_UUID.equals(uuid)
        && (MimeTypes.VIDEO_MP4.equals(schemeMimeType)
        || MimeTypes.AUDIO_MP4.equals(schemeMimeType))) {
      // Prior to API level 26 the ClearKey CDM only accepted "cenc" as the scheme for MP4.
      schemeMimeType = CENC_SCHEME_MIME_TYPE;
    }
    return schemeMimeType;
  }

  @SuppressLint("HandlerLeak")
  private class MediaDrmHandler extends Handler {

    public MediaDrmHandler(Looper looper) {
      super(looper);
    }

    @Override
    public void handleMessage(Message msg) {
      byte[] sessionId = (byte[]) msg.obj;
      for (DefaultDrmSession<T> session : sessions) {
        if (session.hasSessionId(sessionId)) {
          session.onMediaDrmEvent(msg.what);
          return;
        }
      }
    }

  }

  private class MediaDrmEventListener implements OnEventListener<T> {

    @Override
    public void onEvent(ExoMediaDrm<? extends T> md, byte[] sessionId, int event, int extra,
        byte[] data) {
      if (mode == DefaultDrmSessionManager.MODE_PLAYBACK) {
        mediaDrmHandler.obtainMessage(event, sessionId).sendToTarget();
      }
    }

  }

}