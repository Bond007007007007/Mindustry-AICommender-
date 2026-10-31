package aicommander;

import arc.util.serialization.Base64Coder;
import arc.util.serialization.Jval;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class ModelClient{
    private static final int CONNECT_TIMEOUT = 5000;
    private static final int READ_TIMEOUT = 90000;

    private final String baseUrl;
    private final String auth;
    private final String provider;
    private final String model;

    public ModelClient(String baseUrl, String username, String password, String provider, String model){
        this.baseUrl = baseUrl;
        this.provider = provider;
        this.model = model;
        this.auth = basicAuth(username, password);
    }

    public String createSession() throws IOException{
        String response = post("/session", "{\"title\":\"AI Commander\"}");
        Jval json = Jval.read(response);
        String id = json.getString("id", null);
        if(id == null || id.isEmpty()){
            throw new IOException("opencode did not return a session id");
        }
        return id;
    }

    public String prompt(String sessionId, String text) throws IOException{
        Jval modelObject = Jval.newObject();
        modelObject.put("providerID", provider);
        modelObject.put("modelID", model);

        Jval part = Jval.newObject();
        part.put("type", "text");
        part.put("text", text);

        Jval parts = Jval.newArray();
        parts.add(part);

        Jval request = Jval.newObject();
        request.put("model", modelObject);
        request.put("parts", parts);

        String response = post("/session/" + sessionId + "/message", request.toString());
        Jval json = Jval.read(response);
        Jval output = json.get("parts");
        if(output == null || !output.isArray()){
            throw new IOException("opencode response has no parts array");
        }

        StringBuilder result = new StringBuilder();
        for(Jval item : output.asArray()){
            if("text".equals(item.getString("type", ""))){
                result.append(item.getString("text", ""));
            }
        }

        if(result.length() == 0){
            throw new IOException("opencode response has no text part");
        }

        return result.toString();
    }

    private String post(String path, String body) throws IOException{
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        URL url = new URL(baseUrl + path);

        if("https".equalsIgnoreCase(url.getProtocol())){
            return postHttps(url, payload);
        }

        return postRaw(url, payload);
    }

    private String postHttps(URL url, byte[] payload) throws IOException{
        HttpURLConnection connection = (HttpURLConnection)url.openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT);
        connection.setReadTimeout(READ_TIMEOUT);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        if(!auth.isEmpty()){
            connection.setRequestProperty("Authorization", "Basic " + auth);
        }

        try(OutputStream out = connection.getOutputStream()){
            out.write(payload);
        }

        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String response = stream == null ? "" : readAll(stream);

        if(status < 200 || status >= 300){
            throw new IOException("opencode HTTP " + status + ": " + truncate(response, 500));
        }

        return response;
    }

    private String postRaw(URL url, byte[] payload) throws IOException{
        String host = url.getHost();
        int port = url.getPort() == -1 ? 80 : url.getPort();
        String path = url.getFile();
        if(path == null || path.isEmpty()) path = "/";

        Socket socket = new Socket();
        try{
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT);
            socket.setSoTimeout(READ_TIMEOUT);

            StringBuilder head = new StringBuilder();
            head.append("POST ").append(path).append(" HTTP/1.1\r\n");
            head.append("Host: ").append(host).append(':').append(port).append("\r\n");
            head.append("Content-Type: application/json\r\n");
            head.append("Content-Length: ").append(payload.length).append("\r\n");
            head.append("Connection: close\r\n");
            if(!auth.isEmpty()){
                head.append("Authorization: Basic ").append(auth).append("\r\n");
            }
            head.append("\r\n");

            OutputStream out = socket.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.UTF_8));
            out.write(payload);
            out.flush();

            return readHttpResponse(socket.getInputStream());
        }finally{
            try{
                socket.close();
            }catch(IOException ignored){
            }
        }
    }

    private String readHttpResponse(InputStream input) throws IOException{
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int state = 0;
        while(true){
            int value = input.read();
            if(value < 0){
                throw new IOException("connection closed before HTTP headers");
            }
            header.write(value);

            if(state == 0 && value == '\r'){
                state = 1;
            }else if(state == 1 && value == '\n'){
                state = 2;
            }else if(state == 2 && value == '\r'){
                state = 3;
            }else if(state == 3 && value == '\n'){
                break;
            }else{
                state = 0;
            }
        }

        String head = new String(header.toByteArray(), StandardCharsets.UTF_8);
        String[] lines = head.split("\r\n");
        if(lines.length == 0){
            throw new IOException("empty HTTP response");
        }

        String[] statusLine = lines[0].split(" ", 3);
        if(statusLine.length < 2){
            throw new IOException("invalid HTTP status line: " + lines[0]);
        }

        int status;
        try{
            status = Integer.parseInt(statusLine[1]);
        }catch(NumberFormatException e){
            throw new IOException("invalid HTTP status: " + statusLine[1]);
        }

        Map<String, String> headers = new HashMap<>();
        for(int i = 1; i < lines.length; i++){
            int colon = lines[i].indexOf(':');
            if(colon > 0){
                headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT),
                    lines[i].substring(colon + 1).trim());
            }
        }

        byte[] body;
        String encoding = headers.get("transfer-encoding");
        String length = headers.get("content-length");

        if(encoding != null && encoding.toLowerCase(Locale.ROOT).contains("chunked")){
            body = readChunked(input);
        }else if(length != null){
            int count;
            try{
                count = Integer.parseInt(length);
            }catch(NumberFormatException e){
                throw new IOException("invalid content-length: " + length);
            }
            body = readExactly(input, count);
        }else{
            body = readAllBytes(input);
        }

        String text = new String(body, StandardCharsets.UTF_8);
        if(status < 200 || status >= 300){
            throw new IOException("opencode HTTP " + status + ": " + truncate(text, 500));
        }

        return text;
    }

    private byte[] readChunked(InputStream input) throws IOException{
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while(true){
            String line = readLine(input);
            if(line == null) throw new IOException("unexpected end of chunked response");

            int semicolon = line.indexOf(';');
            if(semicolon >= 0) line = line.substring(0, semicolon);
            line = line.trim();

            int size;
            try{
                size = Integer.parseInt(line, 16);
            }catch(NumberFormatException e){
                throw new IOException("invalid chunk size: " + line);
            }

            if(size == 0){
                readLine(input);
                break;
            }

            byte[] chunk = readExactly(input, size);
            out.write(chunk);

            String crlf = readLine(input);
            if(crlf == null) throw new IOException("missing chunk terminator");
        }
        return out.toByteArray();
    }

    private String readLine(InputStream input) throws IOException{
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while(true){
            int value = input.read();
            if(value < 0) return line.size() == 0 ? null : new String(line.toByteArray(), StandardCharsets.US_ASCII);
            if(value == '\n'){
                byte[] data = line.toByteArray();
                int length = data.length;
                if(length > 0 && data[length - 1] == '\r') length--;
                return new String(data, 0, length, StandardCharsets.US_ASCII);
            }
            line.write(value);
        }
    }

    private byte[] readExactly(InputStream input, int count) throws IOException{
        byte[] out = new byte[count];
        int offset = 0;
        while(offset < count){
            int read = input.read(out, offset, count - offset);
            if(read < 0) throw new IOException("unexpected end of HTTP body");
            offset += read;
        }
        return out;
    }

    private byte[] readAllBytes(InputStream input) throws IOException{
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while((read = input.read(buffer)) >= 0){
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private String readAll(InputStream input) throws IOException{
        return new String(readAllBytes(input), StandardCharsets.UTF_8);
    }

    private static String basicAuth(String username, String password){
        if(username == null || username.isEmpty()) return "";
        String value = username + ":" + (password == null ? "" : password);
        return new String(Base64Coder.encode(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String truncate(String value, int max){
        if(value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }
}
