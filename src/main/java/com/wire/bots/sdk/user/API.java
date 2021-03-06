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

package com.wire.bots.sdk.user;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.wire.bots.sdk.assets.IAsset;
import com.wire.bots.sdk.exceptions.HttpException;
import com.wire.bots.sdk.models.AssetKey;
import com.wire.bots.sdk.models.otr.*;
import com.wire.bots.sdk.server.model.Conversation;
import com.wire.bots.sdk.server.model.Member;
import com.wire.bots.sdk.server.model.Service;
import com.wire.bots.sdk.server.model.User;
import com.wire.bots.sdk.tools.Logger;
import com.wire.bots.sdk.tools.Util;
import com.wire.bots.sdk.user.model.Connection;

import javax.annotation.Nullable;
import javax.validation.constraints.NotNull;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

public class API extends LoginClient {
    private final String token;
    private final String convId;

    public API(String convId, String token) {
        this.convId = convId;
        this.token = token;
    }

    static String renewAccessToken(String cookie, String token) throws HttpException {
        Response response = accessPath.
                request(MediaType.APPLICATION_JSON).
                header("Authorization", "Bearer " + token).
                header("Cookie", cookie).
                post(Entity.entity(new Connection(), MediaType.APPLICATION_JSON));


        if (response.getStatus() >= 400) {
            throw new HttpException(response.readEntity(String.class), response.getStatus());
        }

        return response.readEntity(com.wire.bots.sdk.user.model.User.class).getToken();
    }

    public static Conversation createConversation(String name, String token, @Nullable Collection<String> participants)
            throws HttpException {
        _NewConv newConv = new _NewConv();
        newConv.name = name;
        newConv.users = participants;

        Response response = conversationsPath.
                request(MediaType.APPLICATION_JSON).
                header("Authorization", "Bearer " + token).
                post(Entity.entity(newConv, MediaType.APPLICATION_JSON));

        if (response.getStatus() >= 400) {
            throw new HttpException(response.readEntity(String.class), response.getStatus());
        }

        _Conv conv = response.readEntity(_Conv.class);

        Conversation ret = new Conversation();
        ret.name = conv.name;
        ret.id = conv.id;
        ret.members = conv.members.others;
        return ret;
    }

    Devices sendMessage(OtrMessage msg, boolean ignoreMissing) throws HttpException {
        Response response = conversationsPath.
                path(convId).
                path("otr/messages").
                queryParam("ignore_missing", ignoreMissing).
                request(MediaType.APPLICATION_JSON).
                header("Authorization", "Bearer " + token).
                post(Entity.entity(msg, MediaType.APPLICATION_JSON));

        int statusCode = response.getStatus();
        if (statusCode == 412) {
            //Logger.info(response.readEntity(String.class));
            return response.readEntity(Devices.class);
        }

        if (statusCode >= 400)
            throw new HttpException(response.readEntity(String.class), response.getStatus());

        return new Devices();
    }

    PreKeys getPreKeys(Missing missing) {
        if (missing.isEmpty())
            return new PreKeys();

        return usersPath.path("prekeys").
                request(MediaType.APPLICATION_JSON).
                header("Authorization", "Bearer " + token).
                accept(MediaType.APPLICATION_JSON).
                post(Entity.entity(missing, MediaType.APPLICATION_JSON), PreKeys.class);
    }

    byte[] downloadAsset(String assetKey, String assetToken) throws HttpException {
        Invocation.Builder req = assetsPath
                .path(assetKey)
                .request()
                .header("Authorization", "Bearer " + token);

        if (assetToken != null)
            req.header("Asset-Token", assetToken);

        Response response = req.get();

        if (response.getStatus() >= 300) {
            String log = String.format("%s. AssetId: %s", response.readEntity(String.class), assetKey);
            throw new HttpException(log, response.getStatus());
        }

        return response.readEntity(byte[].class);
    }

    void acceptConnection(String user) throws HttpException {
        Connection connection = new Connection();
        connection.setStatus("accepted");

        Response response = connectionsPath.
                path(user).
                request(MediaType.APPLICATION_JSON).
                header("Authorization", "Bearer " + token).
                put(Entity.entity(connection, MediaType.APPLICATION_JSON));

        if (response.getStatus() >= 400) {
            throw new HttpException(response.readEntity(String.class), response.getStatus());
        }
    }

    AssetKey uploadAsset(IAsset asset) throws Exception {
        StringBuilder sb = new StringBuilder();

        // Part 1
        String strMetadata = String.format("{\"public\": %s, \"retention\": \"%s\"}",
                asset.isPublic(),
                asset.getRetention());
        sb.append("--frontier\r\n");
        sb.append("Content-Type: application/json; charset=utf-8\r\n");
        sb.append("Content-Length: ")
                .append(strMetadata.length())
                .append("\r\n\r\n");
        sb.append(strMetadata)
                .append("\r\n");

        // Part 2
        sb.append("--frontier\r\n");
        sb.append("Content-Type: ")
                .append(asset.getMimeType())
                .append("\r\n");
        sb.append("Content-Length: ")
                .append(asset.getEncryptedData().length)
                .append("\r\n");
        sb.append("Content-MD5: ")
                .append(Util.calcMd5(asset.getEncryptedData()))
                .append("\r\n\r\n");

        // Complete
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        os.write(sb.toString().getBytes("utf-8"));
        os.write(asset.getEncryptedData());
        os.write("\r\n--frontier--\r\n".getBytes("utf-8"));

        Response response = assetsPath
                .request(MediaType.APPLICATION_JSON_TYPE)
                .header("Authorization", "Bearer " + token)
                .post(Entity.entity(os.toByteArray(), "multipart/mixed; boundary=frontier"));

        if (response.getStatus() >= 300) {
            throw new HttpException(response.readEntity(String.class), response.getStatus());
        }

        return response.readEntity(AssetKey.class);
    }

    Conversation getConversation() throws IOException {
        Response response = conversationsPath.
                path(convId).
                request().
                header("Authorization", "Bearer " + token).
                get();

        if (response.getStatus() >= 300) {
            Logger.warning(response.readEntity(String.class));
            throw new IOException(response.getStatusInfo().getReasonPhrase());
        }

        _Conv conv = response.readEntity(_Conv.class);

        Conversation ret = new Conversation();
        ret.name = conv.name;
        ret.id = conv.id;
        ret.members = conv.members.others;
        return ret;
    }

    public boolean deleteConversation(String teamId) throws HttpException {
        Response response = teamsPath.
                path(teamId).
                path("conversations").
                path(convId).
                request().
                header("Authorization", "Bearer " + token).
                delete();

        if (response.getStatus() >= 400) {
            throw new HttpException(response.readEntity(String.class), response.getStatus());
        }

        return response.getStatus() == 200;
    }

    public User addService(String serviceId, String providerId) throws IOException {
        _Service service = new _Service();
        service.service = serviceId;
        service.provider = providerId;

        Response response = conversationsPath.
                path(convId).
                path("bots").
                request().
                header("Authorization", "Bearer " + token).
                post(Entity.entity(service, MediaType.APPLICATION_JSON));

        if (response.getStatus() >= 300) {
            Logger.warning(response.readEntity(String.class));
            throw new IOException(response.getStatusInfo().getReasonPhrase());
        }

        User user = response.readEntity(User.class);
        user.service = new Service();
        user.service.id = serviceId;
        user.service.provider = providerId;
        return user;
    }

    public User addParticipants(String... userIds) throws IOException {
        _NewConv newConv = new _NewConv();
        newConv.users = Arrays.asList(userIds);

        Response response = conversationsPath.
                path(convId).
                path("members").
                request().
                header("Authorization", "Bearer " + token).
                post(Entity.entity(newConv, MediaType.APPLICATION_JSON));

        if (response.getStatus() >= 300) {
            Logger.warning(response.readEntity(String.class));
            throw new IOException(response.getStatusInfo().getReasonPhrase());
        }

        return response.readEntity(User.class);
    }

    Collection<com.wire.bots.sdk.server.model.User> getUsers(Collection<String> ids) {
        return usersPath.
                queryParam("ids", ids).
                request(MediaType.APPLICATION_JSON).
                header("Authorization", "Bearer " + token).
                get(new GenericType<ArrayList<com.wire.bots.sdk.server.model.User>>() {
                });
    }

    void uploadPreKeys(ArrayList<PreKey> preKeys) {
        usersPath.path("prekeys").
                request(MediaType.APPLICATION_JSON).
                header("Authorization", "Bearer " + token).
                accept(MediaType.APPLICATION_JSON).
                post(Entity.entity(preKeys, MediaType.APPLICATION_JSON));
    }

    ArrayList<Integer> getAvailablePrekeys(String clientId) {
        return clientsPath.
                path(clientId).
                path("prekeys").
                request().
                header("Authorization", "Bearer " + token).
                accept(MediaType.APPLICATION_JSON).
                get(new GenericType<ArrayList<Integer>>() {
                });
    }

    public String getTeam() {
        _Teams res = teamsPath.request(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + token)
                .accept(MediaType.APPLICATION_JSON)
                .get(_Teams.class);
        if (res.teams.isEmpty())
            return null;

        return res.teams.get(0).id;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class _Conv {
        @JsonProperty
        public String id;

        @JsonProperty
        public String name;

        @JsonProperty
        public _Members members;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class _Members {
        @JsonProperty
        public List<Member> others;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class _Service {
        public String service;
        public String provider;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class _Team {
        @JsonProperty
        public String id;
        @JsonProperty
        public String name;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class _Teams {
        @JsonProperty
        public ArrayList<_Team> teams;
    }

    static class _NewConv {
        @JsonProperty
        @NotNull
        public String name;

        @JsonProperty
        public Collection<String> users;
    }
}
