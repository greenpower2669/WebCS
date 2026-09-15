const video = document.querySelector('#video');
const canvas = document.querySelector('#sampleCanvas');
const ctx = canvas.getContext('2d', { willReadFrequently: true });
const app = document.querySelector('#app');
const startBtn = document.querySelector('#startBtn');
const scanBtn = document.querySelector('#scanBtn');
const ecoBtn = document.querySelector('#ecoBtn');
const exportBtn = document.querySelector('#exportBtn');
const recordToggle = document.querySelector('#recordToggle');
const labelSelect = document.querySelector('#labelSelect');
const statusEl = document.querySelector('#status');
const decisionEl = document.querySelector('#decision');
const rgbEl = document.querySelector('#rgb');
const hsvEl = document.querySelector('#hsv');
const scoreEl = document.querySelector('#score');
const samplesEl = document.querySelector('#samples');

let stream = null;
let eco = false;
let score = Number(localStorage.getItem('webcs.score') || 0);
let scanCount = 0;
let dataset = JSON.parse(localStorage.getItem('webcs.dataset') || '[]');

scoreEl.textContent = `Score ${score}`;

function clamp(v, min = 0, max = 255) {
  return Math.max(min, Math.min(max, v));
}

function rgbToHsv(r, g, b) {
  r /= 255; g /= 255; b /= 255;
  const max = Math.max(r, g, b), min = Math.min(r, g, b);
  const d = max - min;
  let h = 0;
  if (d !== 0) {
    if (max === r) h = 60 * (((g - b) / d) % 6);
    else if (max === g) h = 60 * (((b - r) / d) + 2);
    else h = 60 * (((r - g) / d) + 4);
  }
  if (h < 0) h += 360;
  const s = max === 0 ? 0 : d / max;
  return { h, s, v: max };
}

function isBlue({ h, s, v }) {
  return h >= 185 && h <= 255 && s >= 0.22 && v >= 0.10;
}

function readPoint(imageData, x, y) {
  const { data, width, height } = imageData;
  x = Math.round(clamp(x, 0, width - 1));
  y = Math.round(clamp(y, 0, height - 1));
  const i = (y * width + x) * 4;
  const rgb = { r: data[i], g: data[i + 1], b: data[i + 2] };
  return { ...rgb, ...rgbToHsv(rgb.r, rgb.g, rgb.b) };
}

function analyseCenter() {
  if (!video.videoWidth || !video.videoHeight) return null;

  canvas.width = video.videoWidth;
  canvas.height = video.videoHeight;
  ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
  const image = ctx.getImageData(0, 0, canvas.width, canvas.height);
  const cx = image.width / 2;
  const cy = image.height / 2;
  const gap = Math.max(2, Math.round(Math.min(image.width, image.height) * 0.008));
  const offsets = [[0, 0], [-gap, -gap], [gap, -gap], [-gap, gap], [gap, gap]];
  const points = offsets.map(([dx, dy]) => readPoint(image, cx + dx, cy + dy));
  const blueVotes = points.filter(isBlue).length;
  const avg = points.reduce((a, p) => ({ r: a.r + p.r, g: a.g + p.g, b: a.b + p.b }), { r: 0, g: 0, b: 0 });
  avg.r = Math.round(avg.r / points.length);
  avg.g = Math.round(avg.g / points.length);
  avg.b = Math.round(avg.b / points.length);
  const hsv = rgbToHsv(avg.r, avg.g, avg.b);

  return {
    timestamp: new Date().toISOString(),
    hit: blueVotes >= 3,
    confidence: blueVotes / points.length,
    blueVotes,
    avg: { ...avg, ...hsv },
    points
  };
}

function persistDataset() {
  localStorage.setItem('webcs.dataset', JSON.stringify(dataset));
}

function recordSample(result) {
  dataset.push({ ...result, label: labelSelect.value });
  if (dataset.length > 5000) dataset = dataset.slice(-5000);
  persistDataset();
}

function updateUi(result) {
  scanCount++;
  samplesEl.textContent = `${scanCount} scans`;
  decisionEl.textContent = result.hit ? `BLEU ${Math.round(result.confidence * 100)}%` : `NON ${Math.round(result.confidence * 100)}%`;
  rgbEl.textContent = `RGB ${result.avg.r}/${result.avg.g}/${result.avg.b}`;
  hsvEl.textContent = `HSV ${Math.round(result.avg.h)}°/${Math.round(result.avg.s * 100)}%/${Math.round(result.avg.v * 100)}%`;
  statusEl.textContent = `${result.blueVotes}/5 points classés bleus. ${recordToggle.checked ? `Échantillon enregistré avec l'étiquette « ${labelSelect.value} ».` : 'REC désactivé.'}`;
}

function scan() {
  const result = analyseCenter();
  if (!result) return;
  if (result.hit) {
    score++;
    localStorage.setItem('webcs.score', String(score));
    scoreEl.textContent = `Score ${score}`;
  }
  if (recordToggle.checked) recordSample(result);
  updateUi(result);
}

async function startCamera() {
  try {
    if (stream) stream.getTracks().forEach(t => t.stop());
    stream = await navigator.mediaDevices.getUserMedia({
      audio: false,
      video: {
        facingMode: { ideal: 'environment' },
        width: { ideal: 640 },
        height: { ideal: 480 },
        frameRate: { ideal: 12, max: 20 }
      }
    });
    video.srcObject = stream;
    await video.play();
    startBtn.textContent = 'Relancer caméra';
    scanBtn.disabled = false;
    ecoBtn.disabled = false;
    statusEl.textContent = 'Caméra prête. Le traitement reste local dans le navigateur.';
  } catch (err) {
    statusEl.textContent = `Impossible d'ouvrir la caméra : ${err.message}`;
  }
}

function toggleEco() {
  eco = !eco;
  app.classList.toggle('eco', eco);
  ecoBtn.textContent = eco ? 'Quitter mode éco' : 'Mode éco';
  statusEl.textContent = eco
    ? 'Mode éco : aperçu presque noir, analyse centrale toujours disponible. Sur mobile, baisse aussi la luminosité système pour mesurer le gain.'
    : 'Aperçu normal restauré.';
}

function exportCsv() {
  const header = ['timestamp','label','hit','confidence','blueVotes','avgR','avgG','avgB','avgH','avgS','avgV'];
  const pointHeader = Array.from({ length: 5 }, (_, i) => [`p${i}R`,`p${i}G`,`p${i}B`,`p${i}H`,`p${i}S`,`p${i}V`]).flat();
  const lines = [[...header, ...pointHeader].join(',')];
  for (const row of dataset) {
    const base = [row.timestamp,row.label,row.hit,row.confidence,row.blueVotes,row.avg.r,row.avg.g,row.avg.b,row.avg.h,row.avg.s,row.avg.v];
    const pts = row.points.flatMap(p => [p.r,p.g,p.b,p.h,p.s,p.v]);
    lines.push([...base, ...pts].join(','));
  }
  const blob = new Blob([lines.join('\n')], { type: 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `webcs-dataset-${new Date().toISOString().replace(/[:.]/g,'-')}.csv`;
  a.click();
  URL.revokeObjectURL(url);
}

startBtn.addEventListener('click', startCamera);
scanBtn.addEventListener('click', scan);
ecoBtn.addEventListener('click', toggleEco);
exportBtn.addEventListener('click', exportCsv);

document.addEventListener('keydown', (event) => {
  if (event.code === 'Space' && !scanBtn.disabled) {
    event.preventDefault();
    scan();
  }
});

if ('serviceWorker' in navigator) {
  navigator.serviceWorker.register('./sw.js').catch(() => {});
}
