// node build-standalone.js  → ينتج scanner-standalone.html (ملف واحد بدون اعتماديات)
const fs = require('fs');
let s = fs.readFileSync('index.html', 'utf8');
const inline = (f) => '<script>\n' + fs.readFileSync(f, 'utf8').replace(/<\/script>/g, '<\\/script>') + '\n</script>';
s = s.replace('<script src="analysis.js"></script>', () => inline('analysis.js')).replace('<script src="app.js"></script>', () => inline('app.js'));
s = s.replace(/<link rel="(manifest|icon|apple-touch-icon)"[^>]*>\n/g, '').replace(/<script>if\("serviceWorker".*?<\/script>\n?/s, '');
fs.writeFileSync('scanner-standalone.html', s);
console.log('ok', s.length);
