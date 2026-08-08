// Shared in-memory state across routes
module.exports = {
    sessionContacts:      [],
    pendingContactsSync:  null, // { contacts, setAt } — picked up by device on next poll
    pendingDial:          null, // { contactId, name, phone, queuedAt }
};
