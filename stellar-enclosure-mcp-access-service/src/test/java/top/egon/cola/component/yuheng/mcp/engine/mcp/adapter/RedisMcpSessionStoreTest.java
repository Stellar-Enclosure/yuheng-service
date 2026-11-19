package top.egon.cola.component.yuheng.mcp.engine.mcp.adapter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import top.egon.cola.component.yuheng.mcp.common.transport.McpSessionStore.Session;
import java.time.*;
import org.redisson.api.RedissonClient;

class RedisMcpSessionStoreTest {

    @Test
    void initialStreamReadUsesAValidExclusiveRedisOffset() {
        assertEquals(
                "0-0",
                RedisMcpSessionStore.initialReadOffset().toString()
        );
    }

    private RedisMcpSessionStore store(){return new RedisMcpSessionStore(mock(RedissonClient.class),new ObjectMapper(),"mcp-test:",100,Clock.systemUTC());}
    private String encode(RedisMcpSessionStore store,Session session)throws Exception{var method=RedisMcpSessionStore.class.getDeclaredMethod("encode",Session.class);method.setAccessible(true);return (String)method.invoke(store,session);}
    private Session decode(RedisMcpSessionStore store,String json)throws Exception{var method=RedisMcpSessionStore.class.getDeclaredMethod("decode",String.class);method.setAccessible(true);return (Session)method.invoke(store,json);}
    @Test void exactCatalogIssuerAndResourcePinsRoundTripWithoutCredentials() throws Exception {
        var store=store();var now=Instant.parse("2026-11-19T12:00:00Z");
        var expected=new Session("session-1","billing","user-1","1001","client-1",now,"1001:1:"+"a".repeat(64)+":"+"b".repeat(64),"https://issuer.example/tenants/1001","https://resource.example/mcp/billing");
        var json=encode(store,expected);assertEquals(expected,decode(store,json));assertTrue(expected.pinned());
        assertFalse(json.contains("Bearer"));assertFalse(json.contains("verified-context"));
    }
    @Test void oldUnpinnedSessionsDecodeButCannotInventHistory() throws Exception {
        var session=decode(store(),"{\"sessionId\":\"s\",\"serverCode\":\"billing\",\"subjectId\":\"user\",\"tenantId\":\"1001\",\"clientId\":\"client\",\"createdAt\":\"2026-11-19T12:00:00Z\"}");
        assertFalse(session.pinned());assertNull(session.snapshotIdentity());
    }
    @Test void partialNonStringDuplicateAndUnknownPinMaterialIsRejected() throws Exception {
        var store=store();var json=encode(store,new Session("s","billing","user","1001","client",Instant.parse("2026-11-19T12:00:00Z")));
        for(String bad:java.util.List.of(json.replace("\"snapshotIdentity\":null","\"snapshotIdentity\":\"pin\""),
                json.replace("\"issuer\":null","\"issuer\":1"),json.replace("\"sessionId\":\"s\"","\"sessionId\":\"s\",\"sessionId\":\"other\""),
                json.substring(0,json.length()-1)+",\"rawBearer\":\"forged\"}",json+" {}")) {
            assertThrows(java.lang.reflect.InvocationTargetException.class,()->decode(store,bad));
        }
    }
}
