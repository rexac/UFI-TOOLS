const express = require('express');
const { createProxyMiddleware } = require('http-proxy-middleware');
const path = require('path');

const app = express();

app.use('/api', createProxyMiddleware({
  target: 'http://ufi.ztedevice.com:2333/api',
  changeOrigin: false,
}));

app.use('/', express.static(path.join(__dirname, '/public')));

app.listen(3000, () => {
  console.log('Dev server running at http://localhost:3000');
});