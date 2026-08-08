const express = require('express');
const cors    = require('cors');
const path    = require('path');
const contactsRouter = require('./routes/contacts');
const dialRouter     = require('./routes/dial');

const app  = express();
const PORT = process.env.PORT || 3000;

app.use(cors());
app.use(express.json());

// Serve the backoffice UI
app.use(express.static(path.join(__dirname, '..')));

// API routes
app.use('/api/contacts', contactsRouter);
app.use('/api/dial',     dialRouter);

app.get('/api/status', (req, res) =>
{
    res.json({ ok: true, uptime: process.uptime() });
});

app.listen(PORT, () =>
{
    console.log(`AutoDialer server running at http://localhost:${PORT}`);
});
