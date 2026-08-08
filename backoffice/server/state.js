// Shared in-memory state across routes
module.exports = {
    sessionContacts:      [],
    pendingContactsSync:  null, // { contacts, setAt } — picked up by device on next poll
    pendingDial:          null, // { contactId, name, phone, queuedAt }
    pendingDncSync:       null, // { phones, setAt } — picked up by device on next poll
    dncSet:               new Set(), // normalised digits-only phone numbers; populated by routes/dnc.js
};
