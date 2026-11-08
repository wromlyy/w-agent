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
    @Test public void workflowsDispatchAndRunStatusUseScopedGithubConnection()throws Exception{
        JSONObject service=new JSONObject().put("id","gh").put("url","https://api.github.com").put("token","github-test"),settings=new JSONObject().put("services",new JSONArray().put(service));
        JSONObject a=new JSONObject().put("connection","gh").put("repo","owner/project").put("workflow","android.yml").put("ref","main").put("action","dispatch");
        responses.add(new Mock("",204,"application/json"));assertTrue(new GitHubBuilds(null).execute(settings,a,()->false).contains("НЕ означает"));assertEquals("main",sent.get(0).json().getString("ref"));assertEquals("Bearer github-test",sent.get(0).getRequestProperty("Authorization"));
        respond("{\"status\":\"completed\",\"conclusion\":\"failure\"}");a.put("action","run").put("run_id",42);assertTrue(new GitHubBuilds(null).execute(settings,a,()->false).contains("failure"));assertEquals("/repos/owner/project/actions/runs/42",sent.get(1).getURL().getPath());
        assertThrows(IllegalArgumentException.class,()->GitHubBuilds.repo("../escape"));
    }
    @Test public void compatibleKeepsToolCallIdentityAndDoesNotPutKeyInBody()throws Exception{
        respond("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"device\",\"arguments\":\"{\\\"action\\\":\\\"read_screen\\\"}\"}}]}}]}");
        JSONObject answer=Models.complete(profile("compatible"),messages(),tools(),()->false);
        assertEquals("c1",answer.getJSONArray("tool_calls").getJSONObject(0).getString("id"));
        Mock r=sent.get(0);assertEquals("/v1/chat/completions",r.getURL().getPath());assertEquals("Bearer test-secret",r.getRequestProperty("Authorization"));assertFalse(r.json().toString().contains("test-secret"));assertFalse(r.json().getBoolean("parallel_tool_calls"));
    }
    @Test public void textOnlyAttachmentsUseStringsForCompatibleProvidersAndKeepImages()throws Exception{
        JSONArray ms=messages();ms.getJSONObject(1).put("content",new JSONArray().put(new JSONObject().put("type","text").put("text","task")).put(new JSONObject().put("type","text").put("text","file content")));
        respond("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}");Models.complete(profile("compatible"),ms,tools(),()->false);
        assertEquals("task\nfile content",sent.get(0).json().getJSONArray("messages").getJSONObject(1).getString("content"));
        assertTrue(ms.getJSONObject(1).get("content") instanceof JSONArray);
        ms.getJSONObject(1).getJSONArray("content").put(new JSONObject().put("type","image_url").put("image_url",new JSONObject().put("url","data:image/png;base64,YQ==")));
        respond("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}");Models.complete(profile("compatible"),ms,tools(),()->false);
        assertTrue(sent.get(1).json().getJSONArray("messages").getJSONObject(1).get("content") instanceof JSONArray);
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
    private JSONObject catalogModel(String id,String prompt,String completion,boolean tools)throws Exception{
        return new JSONObject().put("id",id).put("name",id).put("pricing",new JSONObject().put("prompt",prompt).put("completion",completion))
            .put("architecture",new JSONObject().put("output_modalities",new JSONArray().put("text")).put("input_modalities",new JSONArray().put("text")))
            .put("supported_parameters",tools?new JSONArray().put("tools"):new JSONArray());
    }
    @Test public void freeCatalogExcludesPaidHiddenFeesUnknownPricesAndNonTextModels()throws Exception{
        JSONArray all=new JSONArray().put(catalogModel("free-agent","0","0",true)).put(catalogModel("free-chat","0.0","0",false)).put(catalogModel("paid:free","0.001","0",true));
        JSONObject hidden=catalogModel("hidden","0","0",true);hidden.getJSONObject("pricing").put("request","0.1");all.put(hidden);
        JSONObject unknown=catalogModel("unknown","0","0",true);unknown.getJSONObject("pricing").remove("completion");all.put(unknown);
        JSONObject image=catalogModel("image","0","0",true);image.getJSONObject("architecture").put("output_modalities",new JSONArray().put("image"));all.put(image);
        JSONArray free=Models.freeCatalog(all);assertEquals(2,free.length());assertTrue(free.getJSONObject(0).getBoolean("tools"));assertFalse(free.getJSONObject(1).getBoolean("tools"));
    }
    @Test public void freeRequestsRevalidateCatalogAndSetZeroMaximumPrice()throws Exception{
        respond(new JSONObject().put("data",new JSONArray().put(catalogModel("openrouter/free","0","0",true))).toString());
        respond("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hello\"}}]}");
        JSONObject p=profile("compatible").put("base","https://openrouter.ai/api/v1").put("model","openrouter/free").put("freeOnly",true);
        Models.complete(p,messages(),tools(),()->false);
        assertEquals("/api/v1/models",sent.get(0).getURL().getPath());assertNull(sent.get(0).getRequestProperty("Authorization"));
        JSONObject request=sent.get(1).json();assertEquals(0,request.getJSONObject("provider").getJSONObject("max_price").getInt("prompt"));assertFalse(request.has("models"));
        respond(new JSONObject().put("data",new JSONArray().put(catalogModel("openrouter/free","0.01","0",true))).toString());
        try{Models.complete(p,messages(),tools(),()->false);fail();}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("бесплатная"));}
        assertEquals(3,sent.size());
    }
    @Test public void chatOnlyOmitsUnsupportedToolFieldsAcrossProviders()throws Exception{
        for(String type:new String[]{"compatible","anthropic","gemini"}){
            if("compatible".equals(type))respond("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hello\"}}]}");
            if("anthropic".equals(type))respond("{\"content\":[{\"type\":\"text\",\"text\":\"hello\"}]}");
            if("gemini".equals(type))respond("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hello\"}]}}]}");
            Models.complete(profile(type),messages(),new JSONArray(),()->false);JSONObject req=sent.get(sent.size()-1).json();assertFalse(req.has("tools"));assertFalse(req.has("parallel_tool_calls"));
        }
    }
    @Test public void pkceChallengeMatchesRfcVectorAndRejectsExpiredFlowBeforeNetwork()throws Exception{
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",OpenRouterAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
        JSONObject flow=OpenRouterAuth.begin(1000);assertTrue(flow.getString("verifier").length()>=43);
        String url=OpenRouterAuth.url(flow);assertTrue(url.contains("code_challenge_method=S256"));assertFalse(url.contains(flow.getString("verifier")));assertFalse(url.contains("callback_url"));
        try{OpenRouterAuth.exchange(flow,"test-code",601001,()->false);fail();}catch(IllegalArgumentException expected){}
        assertEquals(0,sent.size());
        respond("{\"key\":\"test-user-controlled-key\"}");assertEquals("test-user-controlled-key",OpenRouterAuth.exchange(flow,"test-code",2000,()->false));
        JSONObject request=sent.get(0).json();assertEquals(flow.getString("verifier"),request.getString("code_verifier"));assertEquals("test-code",request.getString("code"));assertNull(sent.get(0).getRequestProperty("Authorization"));
    }
    @Test public void responsesPreservesReasoningItemsAndCallIdsAcrossToolTurns()throws Exception{
        JSONObject p=profile("responses").put("thinking","on").put("effort","xhigh");
        respond("{\"output\":[{\"type\":\"reasoning\",\"id\":\"rs_1\",\"summary\":[],\"encrypted_content\":\"opaque-test\"},{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_r\",\"name\":\"device\",\"arguments\":\"{\\\"action\\\":\\\"read_screen\\\"}\"}]}");
        JSONArray ms=messages();JSONObject answer=Models.complete(p,ms,tools(),()->false);assertEquals("call_r",answer.getJSONArray("tool_calls").getJSONObject(0).getString("id"));
        assertEquals("/v1/responses",sent.get(0).getURL().getPath());assertEquals("xhigh",sent.get(0).json().getJSONObject("reasoning").getString("effort"));assertFalse(sent.get(0).json().getBoolean("store"));
        ms.put(answer).put(new JSONObject().put("role","tool").put("tool_call_id","call_r").put("content","screen"));
        respond("{\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"Done\"}]}]}");
        assertEquals("Done",Models.complete(p,ms,tools(),()->false).getString("content"));JSONArray input=sent.get(1).json().getJSONArray("input");
        assertEquals("opaque-test",input.getJSONObject(2).getString("encrypted_content"));assertEquals("call_r",input.getJSONObject(4).getString("call_id"));
    }
    @Test public void reasoningMapsToOpenRouterAndAnthropicWithoutLosingSignedContent()throws Exception{
        JSONObject router=profile("compatible").put("base","https://openrouter.ai/api/v1").put("thinking","on").put("effort","high");
        respond("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}");Models.complete(router,messages(),tools(),()->false);
        assertEquals("high",sent.get(0).json().getJSONObject("reasoning").getString("effort"));assertFalse(sent.get(0).json().has("reasoning_effort"));
        JSONObject anthropic=profile("anthropic").put("thinking","on").put("effort","high");
        respond("{\"content\":[{\"type\":\"thinking\",\"thinking\":\"provider-returned\",\"signature\":\"signed-test\"},{\"type\":\"tool_use\",\"id\":\"t2\",\"name\":\"device\",\"input\":{\"action\":\"read_screen\"}}]}");
        JSONArray ms=messages();JSONObject answer=Models.complete(anthropic,ms,tools(),()->false);assertEquals(8192,sent.get(1).json().getJSONObject("thinking").getInt("budget_tokens"));
        ms.put(answer).put(new JSONObject().put("role","tool").put("tool_call_id","t2").put("content","screen"));respond("{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}");Models.complete(anthropic,ms,tools(),()->false);
        assertEquals("signed-test",sent.get(2).json().getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("signature"));
    }
}
