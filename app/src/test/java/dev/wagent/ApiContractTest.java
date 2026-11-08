package dev.wagent;

import org.json.*;
import org.junit.*;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Offline contract checks: real adapters and transport, deterministic HTTPS responses. */
public class ApiContractTest {
    private static final Queue<Mock> responses=new ConcurrentLinkedQueue<>();
    private static final List<Mock> sent=new ArrayList<>();
    private static class Mock extends HttpURLConnection {
        final String body; final int code; final String contentType; final ByteArrayOutputStream request=new ByteArrayOutputStream();
        Mock(String body,int code,String contentType) throws Exception {super(new URL("https://provider.test"));this.body=body;this.code=code;this.contentType=contentType;}
        @Override public void disconnect(){}
        @Override public boolean usingProxy(){return false;}
        @Override public void connect(){}
        @Override public OutputStream getOutputStream(){return request;}
        @Override public int getResponseCode(){return code;}
        @Override public String getContentType(){return contentType;}
        @Override public String getHeaderField(String key){return "Mcp-Session-Id".equals(key)?"session-test":null;}
        @Override public InputStream getInputStream(){return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));}
        @Override public InputStream getErrorStream(){return getInputStream();}
        JSONObject json() throws Exception{return new JSONObject(request.toString("UTF-8"));}
        void target(URL url){this.url=url;}
    }
    @BeforeClass public static void installMockHttps(){URL.setURLStreamHandlerFactory(protocol->"https".equals(protocol)?new URLStreamHandler(){
        @Override protected URLConnection openConnection(URL url)throws IOException{Mock mock=responses.poll();if(mock==null)throw new IOException("Unexpected request "+url);mock.target(url);sent.add(mock);return mock;}
    }:null);}
    @Before public void reset(){responses.clear();sent.clear();}
    private void respond(String body)throws Exception{responses.add(new Mock(body,200,"application/json"));}
    private JSONObject profile(String type)throws Exception{return new JSONObject().put("type",type).put("base","https://provider.test/v1").put("key","test-secret").put("model","test-model");}
    private JSONArray tools()throws Exception{return new JSONArray("[{\"type\":\"function\",\"function\":{\"name\":\"device\",\"description\":\"Device actions\",\"parameters\":{\"type\":\"object\",\"properties\":{\"action\":{\"type\":\"string\"}},\"required\":[\"action\"]}}}]");}
    private JSONArray messages()throws Exception{return new JSONArray("[{\"role\":\"system\",\"content\":\"You are W\"},{\"role\":\"user\",\"content\":\"Read screen\"}]");}
    @Test public void compatibleKeepsToolCallIdentityAndDoesNotPutKeyInBody()throws Exception{
        respond("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"device\",\"arguments\":\"{\\\"action\\\":\\\"read_screen\\\"}\"}}]}}]}");
        JSONObject answer=Models.complete(profile("compatible"),messages(),tools(),()->false);
        assertEquals("c1",answer.getJSONArray("tool_calls").getJSONObject(0).getString("id"));
        Mock r=sent.get(0);assertEquals("/v1/chat/completions",r.getURL().getPath());assertEquals("Bearer test-secret",r.getRequestProperty("Authorization"));assertFalse(r.json().toString().contains("test-secret"));assertFalse(r.json().getBoolean("parallel_tool_calls"));
    }
    @Test public void anthropicTranslatesImagesToolUseAndToolResults()throws Exception{
        respond("{\"content\":[{\"type\":\"text\",\"text\":\"Reading\"},{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"device\",\"input\":{\"action\":\"read_screen\"}}]}");
        JSONArray ms=messages();ms.getJSONObject(1).put("content",new JSONArray("[{\"type\":\"text\",\"text\":\"Look\"},{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,YQ==\"}}]"));
        JSONObject a=Models.complete(profile("anthropic"),ms,tools(),()->false);assertEquals("Reading",a.getString("content"));
        JSONObject req=sent.get(0).json();assertEquals("You are W",req.getString("system"));assertEquals("base64",req.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(1).getJSONObject("source").getString("type"));
        assertEquals("test-secret",sent.get(0).getRequestProperty("x-api-key"));
        ms.put(a).put(new JSONObject().put("role","tool").put("tool_call_id","t1").put("content","screen-result"));respond("{\"content\":[{\"type\":\"text\",\"text\":\"Done\"}]}");
        Models.complete(profile("anthropic"),ms,tools(),()->false);JSONArray translated=sent.get(1).json().getJSONArray("messages");
        assertEquals("t1",translated.getJSONObject(2).getJSONArray("content").getJSONObject(0).getString("tool_use_id"));
    }
    @Test public void geminiPreservesThoughtSignaturesAcrossToolTurns()throws Exception{
        respond("{\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":{\"name\":\"device\",\"args\":{\"action\":\"read_screen\"}},\"thoughtSignature\":\"signature-test\"}]}}]}");
        JSONArray ms=messages();JSONObject a=Models.complete(profile("gemini"),ms,tools(),()->false);JSONObject call=a.getJSONArray("tool_calls").getJSONObject(0);assertEquals("signature-test",call.getString("thoughtSignature"));
        ms.put(a).put(new JSONObject().put("role","tool").put("tool_call_id",call.getString("id")).put("content","screen"));respond("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Done\"}]}}]}");
        Models.complete(profile("gemini"),ms,tools(),()->false);JSONArray c=sent.get(1).json().getJSONArray("contents");assertEquals("signature-test",c.getJSONObject(1).getJSONArray("parts").getJSONObject(0).getString("thoughtSignature"));
        assertEquals("device",c.getJSONObject(2).getJSONArray("parts").getJSONObject(0).getJSONObject("functionResponse").getString("name"));assertEquals("test-secret",sent.get(1).getRequestProperty("x-goog-api-key"));
    }
    @Test public void modelCatalogSupportsGeminiNamesAndAuthentication()throws Exception{
        respond("{\"models\":[{\"name\":\"models/gemini-test\"}]}");assertEquals("gemini-test",Models.models(profile("gemini"),()->false).getString(0));assertEquals("test-secret",sent.get(0).getRequestProperty("x-goog-api-key"));
    }
    @Test public void mcpInitializesSessionAndReadsSseResponse()throws Exception{
        respond("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{}}}");responses.add(new Mock("",202,"application/json"));
        responses.add(new Mock("event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[{\"name\":\"hello\"}]}}\n\n",200,"text/event-stream"));
        JSONObject service=new JSONObject().put("id","mcp-contract").put("type","mcp").put("url","https://provider.test/mcp").put("token","mcp-secret");
        JSONObject r=new JSONObject(Connections.execute(service,new JSONObject().put("operation","tools/list"),()->false));assertEquals("hello",r.getJSONArray("tools").getJSONObject(0).getString("name"));
        assertEquals("initialize",sent.get(0).json().getString("method"));assertFalse(sent.get(1).json().has("id"));assertEquals("session-test",sent.get(2).getRequestProperty("Mcp-Session-Id"));
    }
    @Test public void transportRejectsCleartextAndConnectionEscape()throws Exception{
        try{Net.request("GET","http://provider.test",null,null,()->false);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("HTTPS"));}
        JSONObject service=new JSONObject().put("id","http-contract").put("url","https://provider.test/api").put("token","secret");
        for(String path:new String[]{"https://attacker.test","//attacker.test","../escape","\\attacker"}){try{Connections.execute(service,new JSONObject().put("path",path),()->false);fail(path);}catch(IllegalArgumentException expected){}}
        assertEquals(0,sent.size());
    }
    @Test public void transportRejectsRedirectAndHonorsCancellation()throws Exception{
        responses.add(new Mock("",302,"application/json"));try{Net.request("GET","https://provider.test",null,null,()->false);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("302"));}
        respond("{}");try{Net.request("GET","https://provider.test",null,null,()->true);fail();}catch(InterruptedException expected){}
    }
}
