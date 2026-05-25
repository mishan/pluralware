# Rules consumed by apps that depend on :shared.
# Plural.kt uses Ktor + kotlinx.serialization reflectively; once we wire it up for real
# we'll need to keep its model classes. Add those rules here when we hit R8 issues in :wear.
