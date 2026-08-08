const express = require('express');
const cors    = require('cors');
const path    = require('path');
const fs      = require('fs');
const contactsRouter = require('./routes/contacts');
const dialRouter     = require('./routes/dial');
const deviceRouter   = require('./routes/device');
const dncRouter      = require('./routes/dnc');

const app  = express();
const PORT = process.env.PORT || 3000;

app.use(cors());
app.use(express.json());

// Serve the backoffice UI — ./public when running in Docker, ../ when running locally
const uiDir = fs.existsSync(path.join(__dirname, 'public'))
    ? path.join(__dirname, 'public')
    : path.join(__dirname, '..');
app.use(express.static(uiDir));

// API routes
app.use('/api/contacts', contactsRouter);
app.use('/api/dial',     dialRouter);
app.use('/api/device',   deviceRouter);
app.use('/api/dnc',      dncRouter);

app.get('/api/status', (req, res) =>
{
    res.json({ ok: true, uptime: process.uptime() });
});

app.listen(PORT, () =>
{
    console.log(`AutoDialer server running at http://localhost:${PORT}`);
});