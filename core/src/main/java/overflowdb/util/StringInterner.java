package overflowdb.util;

import com.google.common.collect.Interner;
import com.google.common.collect.Interners;

public class StringInterner {
  private final Interner<String> interner = Interners.newWeakInterner();

  public String intern(String s){
    if (s == null) return null;
    return interner.intern(s);
  }

  /**
   * Clears the interner.
   * With Guava's WeakInterner, explicit clearing is rarely needed as entries
   * are automatically evicted when no longer referenced elsewhere.
   */
  public void clear() {
  }
}