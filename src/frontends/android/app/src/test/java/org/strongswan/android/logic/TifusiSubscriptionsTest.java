package org.strongswan.android.logic;

import android.content.Context;
import android.content.SharedPreferences;
import org.junit.Before;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class TifusiSubscriptionsTest {
    private Context context;
    private final Map<String, Memory> stores = new HashMap<>();
    private static final String A = "https://one.example/sub/aaaaaaaaaaaaaaaa";
    private static final String B = "https://two.example/sub/bbbbbbbbbbbbbbbb";
    @Before public void setup() {
        context = mock(Context.class);
        when(context.getSharedPreferences(anyString(), anyInt())).thenAnswer(i -> stores.computeIfAbsent(i.getArgument(0), k -> new Memory()));
    }
    private Memory root() { return (Memory) context.getSharedPreferences("tifusi", 0); }
    private void add(String link, String name) throws Exception {
        TifusiSubscription.accounts(context);
        String id = UUID.nameUUIDFromBytes(link.substring(link.lastIndexOf("/sub/") + 5).getBytes("UTF-8")).toString();
        context.getSharedPreferences("tifusi_account_" + id, 0).edit().putString("link", link).putString("username", name).putLong("data_limit", name.equals("Alice") ? 100 : 200).commit();
        java.lang.reflect.Method method = TifusiSubscription.class.getDeclaredMethod("register", Context.class, String.class);
        method.setAccessible(true); method.invoke(null, context, link);
    }
    @Test public void legacyAccountKeepsNativeUuidAndQuota() {
        root().edit().putString("link", A).putString("profile_uuid", "original-uuid").putString("username", "Legacy").putLong("data_limit", 987).commit();
        assertEquals(1, TifusiSubscription.accounts(context).size());
        assertEquals(A, TifusiSubscription.savedLink(context));
        assertEquals(987, TifusiSubscription.savedAccount(context).limit);
        String id = root().getString("active_account", null);
        assertEquals("original-uuid", context.getSharedPreferences("tifusi_account_" + id, 0).getString("profile_uuid", null));
    }
    @Test public void secondImportDoesNotOverwriteFirstAccount() throws Exception {
        add(A, "Alice"); add(B, "Bob");
        assertEquals(2, TifusiSubscription.accounts(context).size());
        assertEquals(B, TifusiSubscription.savedLink(context));
        TifusiSubscription.select(context, A);
        assertEquals("Alice", TifusiSubscription.savedAccount(context).username);
        assertEquals(100, TifusiSubscription.savedAccount(context).limit);
        TifusiSubscription.select(context, B);
        assertEquals("Bob", TifusiSubscription.savedAccount(context).username);
        assertEquals(200, TifusiSubscription.savedAccount(context).limit);
    }
    @Test public void repeatedImportAndAlternateDomainDoNotDuplicateAccount() throws Exception {
        add(A, "Alice"); add(A, "Alice");
        assertEquals(1, TifusiSubscription.accounts(context).size());
        assertTrue(TifusiSubscription.contains(context, "https://new.example/sub/aaaaaaaaaaaaaaaa"));
    }
    @Test public void deletingInactiveAccountKeepsActiveAccount() throws Exception {
        add(A, "Alice"); add(B, "Bob");
        TifusiSubscription.remove(context, A);
        assertEquals(B, TifusiSubscription.savedLink(context));
        assertFalse(TifusiSubscription.contains(context, A));
    }
    @Test public void deletingActiveAccountSelectsRemainingAccount() throws Exception {
        add(A, "Alice"); add(B, "Bob");
        TifusiSubscription.remove(context, B);
        assertEquals(A, TifusiSubscription.savedLink(context));
    }
    @Test public void deletingLastLegacyAccountDoesNotResurrectIt() {
        root().edit().putString("link", A).putString("username", "Legacy").commit();
        TifusiSubscription.accounts(context);
        TifusiSubscription.remove(context, A);
        assertTrue(TifusiSubscription.accounts(context).isEmpty());
        assertNull(TifusiSubscription.savedLink(context));
    }
    @Test public void unknownSelectionDoesNotChangeActiveAccount() throws Exception {
        add(A, "Alice");
        TifusiSubscription.select(context, B);
        assertEquals(A, TifusiSubscription.savedLink(context));
    }
    static class Memory implements SharedPreferences {
        final Map<String,Object> values = new HashMap<>();
        public Map<String,?> getAll() { return new HashMap<>(values); }
        public String getString(String k,String d) { return (String)values.getOrDefault(k,d); }
        @SuppressWarnings("unchecked") public Set<String> getStringSet(String k,Set<String>d) { return new HashSet<>((Set<String>)values.getOrDefault(k,d)); }
        public int getInt(String k,int d) { return (Integer)values.getOrDefault(k,d); }
        public long getLong(String k,long d) { return (Long)values.getOrDefault(k,d); }
        public float getFloat(String k,float d) { return (Float)values.getOrDefault(k,d); }
        public boolean getBoolean(String k,boolean d) { return (Boolean)values.getOrDefault(k,d); }
        public boolean contains(String k) { return values.containsKey(k); }
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
        public Editor edit() { return new Editor() {
            final Map<String,Object> pending = new HashMap<>(); boolean clear;
            public Editor putString(String k,String v) { pending.put(k,v);return this; }
            public Editor putStringSet(String k,Set<String>v) { pending.put(k,new HashSet<>(v));return this; }
            public Editor putInt(String k,int v) { pending.put(k,v);return this; }
            public Editor putLong(String k,long v) { pending.put(k,v);return this; }
            public Editor putFloat(String k,float v) { pending.put(k,v);return this; }
            public Editor putBoolean(String k,boolean v) { pending.put(k,v);return this; }
            public Editor remove(String k) { pending.put(k,null);return this; }
            public Editor clear() { clear=true;return this; }
            public boolean commit() { if(clear)values.clear(); pending.forEach((k,v)->{if(v==null)values.remove(k);else values.put(k,v);});return true; }
            public void apply() { commit(); }
        }; }
    }
}
