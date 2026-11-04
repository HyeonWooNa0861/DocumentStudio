import AppKit

protocol StudioEditorSession: AnyObject {
    var window: NSWindow? { get }
    var hasUnsavedChanges: Bool { get }
    var hasActiveOperation: Bool { get }
    func confirmBeforeClosing(completion: @escaping (Bool) -> Void)
}
