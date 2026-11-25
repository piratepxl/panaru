import SwiftUI

/// Equivalente di `@State` che non dipende dal plugin di macro `SwiftUIMacros`.
///
/// Con l'SDK di macOS 27 `@State` è risolto come macro, il cui plugin è distribuito solo con Xcode:
/// usando questo wrapper l'app si compila anche con i soli Command Line Tools (`swift build`).
/// SwiftUI gestisce lo stato perché `State` resta una proprietà `DynamicProperty` annidata.
@propertyWrapper
struct LocalState<Value>: DynamicProperty {
    private let storage: State<Value>

    init(wrappedValue: Value) { storage = State(initialValue: wrappedValue) }

    var wrappedValue: Value {
        get { storage.wrappedValue }
        nonmutating set { storage.wrappedValue = newValue }
    }

    var projectedValue: Binding<Value> { storage.projectedValue }
}
