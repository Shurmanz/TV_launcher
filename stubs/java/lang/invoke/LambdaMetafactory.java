package java.lang.invoke;
// Компиляционная заглушка: d8 «рассахаривает» лямбды, на устройстве класс не нужен.
public final class LambdaMetafactory {
    public static CallSite metafactory(MethodHandles.Lookup c, String n, MethodType t, MethodType s, MethodHandle i, MethodType d) { throw new UnsupportedOperationException(); }
    public static CallSite altMetafactory(MethodHandles.Lookup c, String n, MethodType t, Object... a) { throw new UnsupportedOperationException(); }
}
