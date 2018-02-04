//
// Wire
// Copyright (C) 2016 Wire Swiss GmbH
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program. If not, see http://www.gnu.org/licenses/.
//

package com.wire.bots.sdk;

import com.wire.bots.sdk.assets.*;
import com.wire.bots.sdk.crypto.Crypto;
import com.wire.bots.sdk.models.AssetKey;
import com.wire.bots.sdk.models.otr.*;
import com.wire.bots.sdk.server.model.Conversation;
import com.wire.bots.sdk.server.model.NewBot;
import com.wire.bots.sdk.server.model.User;
import com.wire.bots.sdk.storage.Storage;
import com.wire.bots.sdk.tools.Logger;
import com.wire.bots.sdk.tools.Util;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.UUID;

/**
 *
 */
public class BotClient implements WireClient {
    private final API api;
    private final Crypto crypto;
    private final NewBot state;
    private Devices devices = null;

    public BotClient(Crypto crypto, Storage storage) throws Exception {
        this.state = storage.getState();
        this.api = new API(state.token);
        this.crypto = crypto;
    }

    @Override
    public void sendText(String txt) throws Exception {
        postGenericMessage(new Text(txt));
    }

    @Override
    public void sendText(String txt, long expires) throws Exception {
        postGenericMessage(new Text(txt, expires));
    }

    @Override
    public void sendText(String txt, long expires, String messageId) throws Exception {
        Text text = new Text(txt, expires);
        text.setMessageId(messageId);
        postGenericMessage(text);
    }

    @Override
    public void sendLinkPreview(String url, String title, IGeneric image) throws Exception {
        postGenericMessage(new LinkPreview(url, title, image.createGenericMsg().getAsset()));
    }

    @Override
    public void sendPicture(byte[] bytes, String mimeType) throws Exception {
        Picture image = new Picture(bytes, mimeType);

        AssetKey assetKey = uploadAsset(image);
        image.setAssetKey(assetKey.key);
        image.setAssetToken(assetKey.token);

        postGenericMessage(image);
    }

    @Override
    public void sendPicture(IGeneric image) throws Exception {
        postGenericMessage(image);
    }

    @Override
    public void sendAudio(byte[] bytes, String name, String mimeType, long duration) throws Exception {
        AudioPreview preview = new AudioPreview(bytes, name, mimeType, duration);
        AudioAsset audioAsset = new AudioAsset(bytes, preview);

        postGenericMessage(preview);

        AssetKey assetKey = uploadAsset(audioAsset);
        audioAsset.setAssetKey(assetKey.key);
        audioAsset.setAssetToken(assetKey.token);

        // post original + remote asset message
        postGenericMessage(audioAsset);
    }

    @Override
    public void sendVideo(byte[] bytes, String name, String mimeType, long duration, int h, int w) throws Exception {
        String messageId = UUID.randomUUID().toString();
        VideoPreview preview = new VideoPreview(name, mimeType, duration, h, w, bytes.length, messageId);
        VideoAsset asset = new VideoAsset(bytes, mimeType, messageId);

        postGenericMessage(preview);

        AssetKey assetKey = uploadAsset(asset);
        asset.setAssetKey(assetKey.key);
        asset.setAssetToken(assetKey.token);

        // post original + remote asset message
        postGenericMessage(asset);
    }

    @Override
    public void sendFile(File f, String mime) throws Exception {
        FileAssetPreview preview = new FileAssetPreview(f, mime);
        FileAsset asset = new FileAsset(preview);

        // post preview
        postGenericMessage(preview);

        // upload asset to backend
        AssetKey assetKey = uploadAsset(asset);
        asset.setAssetKey(assetKey.key);
        asset.setAssetToken(assetKey.token);

        // post original + remote asset message
        postGenericMessage(asset);
    }

    @Override
    public void ping() throws Exception {
        postGenericMessage(new Ping());
    }

    @Override
    public void sendOT(OT ot) throws Exception {
        postGenericMessage(ot);
    }

    @Override
    public byte[] downloadAsset(String assetKey, String assetToken, byte[] sha256Challenge, byte[] otrKey)
            throws Exception {
        byte[] cipher = api.downloadAsset(assetKey, assetToken);
        byte[] sha256 = MessageDigest.getInstance("SHA-256").digest(cipher);
        if (!Arrays.equals(sha256, sha256Challenge))
            throw new Exception("Failed sha256 check");

        return Util.decrypt(otrKey, cipher);
    }

    @Override
    public void sendReaction(String msgId, String emoji) throws Exception {
        postGenericMessage(new Reaction(msgId, emoji));
    }

    @Override
    public void deleteMessage(String msgId) throws Exception {
        postGenericMessage(new Delete(msgId));
    }

    @Override
    public String getId() {
        return state.id;
    }

    @Override
    public String getConversationId() {
        return state.conversation.id;
    }

    @Override
    public String getDeviceId() {
        return state.client;
    }

    @Override
    public Collection<User> getUsers(Collection<String> userIds) throws IOException {
        return api.getUsers(userIds);
    }

    @Override
    public Conversation getConversation() throws IOException {
        return api.getConversation();
    }

    @Override
    public void sendDelivery(String msgId) throws Exception {
        postGenericMessage(new Confirmation(msgId));
    }

    @Override
    public void acceptConnection(String user) throws IOException {
        // bots cannot accept connections
    }

    @Override
    public byte[] decrypt(String userId, String clientId, String cypher) throws Exception {
        return crypto.decrypt(userId, clientId, cypher);
    }

    @Override
    public PreKey newLastPreKey() throws Exception {
        return crypto.newLastPreKey();
    }

    @Override
    public ArrayList<PreKey> newPreKeys(int from, int count) throws Exception {
        return crypto.newPreKeys(from, count);
    }

    @Override
    public void uploadPreKeys(ArrayList<PreKey> preKeys) throws IOException {
        api.uploadPreKeys((preKeys));
    }

    @Override
    public ArrayList<Integer> getAvailablePrekeys() {
        return api.getAvailablePrekeys();
    }

    @Override
    public boolean isClosed() {
        return crypto.isClosed();
    }

    @Override
    public byte[] downloadProfilePicture(String assetKey) throws IOException {
        return api.downloadAsset(assetKey, null);
    }

    @Override
    public void close() throws IOException {
        crypto.close();
    }

    @Override
    public AssetKey uploadAsset(IAsset asset) throws Exception {
        return api.uploadAsset(asset);
    }

    /**
     * Encrypt whole message for participants in the conversation.
     * Implements the fallback for the 412 error code and missing
     * devices.
     *
     * @param generic generic message to be sent
     * @throws Exception CryptoBox exception
     */
    private void postGenericMessage(IGeneric generic) throws Exception {
        byte[] content = generic.createGenericMsg().toByteArray();

        // Try to encrypt the msg for those devices that we have the session already
        Recipients encrypt = crypto.encrypt(getDevices().missing, content);
        OtrMessage msg = new OtrMessage(state.client, encrypt);

        Devices res = api.sendMessage(msg);
        if (!res.hasMissing()) {
            // Fetch preKeys for the missing devices from the Backend
            PreKeys preKeys = api.getPreKeys(res.missing);

            // Encrypt msg for those devices that were missing. This time using preKeys
            encrypt = crypto.encrypt(preKeys, content);
            msg.add(encrypt);

            // reset devices so they could be pulled next time
            devices = null;

            res = api.sendMessage(msg, true);
            if (!res.hasMissing()) {
                Logger.error(String.format("Failed to send otr message to %d devices. Bot: %s",
                        res.size(),
                        getId()));
            }
        }
    }

    /**
     * This method will send an empty message to BE and collect the list of missing client ids
     * When empty message is sent the Backend will respond with error 412 and a list of missing clients.
     *
     * @return List of all participants in this conversation and their clientIds
     */
    private Devices getDevices() {
        try {
            if (devices == null || devices.hasMissing()) {
                devices = api.sendMessage(new OtrMessage(state.client, new Recipients()));
            }
        } catch (IOException e) {
            Logger.error(e.getMessage());
            devices = new Devices();
        }
        return devices;
    }
}
