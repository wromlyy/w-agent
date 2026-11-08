package dev.wagent;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class ModelStreamTest {
    @Test public void truncatedToolCallsCannotExecute()throws Exception{
        ModelStream s=new ModelStream("compatible",null);
        s.accept("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"function\":{\"name\":\"files\",\"arguments\":\"{\"}}]}}]}");
        assertThrows(IllegalStateException.class,s::finish);
    }
    @Test public void textIsDeliveredBeforeComplete()throws Exception{
        StringBuilder text=new StringBuilder();ModelStream s=new ModelStream("compatible",text::append);
        s.accept("{\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}");assertEquals("Hello",text.toString());s.accept("[DONE]");
        assertEquals("Hello",s.finish().getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"));
    }
    @Test public void signedThinkingIsReassembledWithoutDisplayingIt()throws Exception{
        StringBuilder text=new StringBuilder();ModelStream s=new ModelStream("anthropic",text::append);
        s.accept("{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}");
        s.accept("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"private\"}}");
        s.accept("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"sig\"}}");s.accept("{\"type\":\"message_stop\"}");
        assertEquals("",text.toString());assertEquals("sig",s.finish().getJSONArray("content").getJSONObject(0).getString("signature"));
    }
}
