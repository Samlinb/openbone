// Optional external-runtime audit. Install the official spine-core package in a private
// target directory; no runtime dependency is added to OpenBone or its distributable.
// node scripts/verify-spine-export.mjs <spine-core/dist/index.js> <evidence-dir> <version>
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { pathToFileURL } from 'node:url';
import { registerHooks } from 'node:module';

// Earlier official packages use bundler-style extensionless ESM imports.
registerHooks({ resolve(specifier, context, next) {
  try { return next(specifier, context); }
  catch (error) {
    if (specifier.startsWith('.') && !specifier.endsWith('.js')) return next(specifier + '.js', context);
    throw error;
  }
} });

const [modulePath, evidence, version = '4.2.22'] = process.argv.slice(2);
if (!modulePath || !evidence) throw new Error('Expected runtime module and export evidence directory');
const runtime = await import(pathToFileURL(resolve(modulePath)).href);
function loader() {
  return new runtime.AtlasAttachmentLoader({ findRegion() {
    const region = new runtime.TextureRegion();
    Object.assign(region, { u: 0, v: 0, u2: 1, v2: 1, width: 1, height: 1, originalWidth: 1, originalHeight: 1 });
    return region;
  } });
}
const samples = JSON.parse(readFileSync(resolve(evidence, 'reference.json')));
const report = [];
for (const format of ['JSON', 'BINARY']) {
  const root = resolve(evidence, version + format);
  const pointer = JSON.parse(readFileSync(resolve(root, 'current.json')));
  const path = format === 'JSON' ? resolve(root, pointer.json) : resolve(root, pointer.generation, 'skeleton.skel');
  const data = format === 'JSON'
    ? new runtime.SkeletonJson(loader()).readSkeletonData(JSON.parse(readFileSync(path)))
    : new runtime.SkeletonBinary(loader()).readSkeletonData(new Uint8Array(readFileSync(path)));
  let maxPosition = 0, maxMatrix = 0, maxVertex = 0, compared = 0;
  let worst = '';
  for (const sample of samples) {
    const skeleton = new runtime.Skeleton(data);
    skeleton.setSkinByName(sample.skin);
    if (skeleton.setupPose) skeleton.setupPose(); else skeleton.setToSetupPose();
    const clip = data.findAnimation(sample.animation);
    if (!clip) throw new Error(`Missing animation ${sample.animation}`);
    if (runtime.MixFrom) clip.apply(skeleton, 0, sample.time, false, [], 1, runtime.MixFrom.setup, false, false, false);
    else clip.apply(skeleton, 0, sample.time, false, [], 1, runtime.MixBlend.replace, runtime.MixDirection.mixIn);
    skeleton.updateWorldTransform(runtime.Physics.pose);
    for (const [name, expected] of Object.entries(sample.bones)) {
      const bone = skeleton.findBone(name);
      if (!bone?.active) throw new Error(`Inactive or missing exported bone: ${sample.skin}/${name}`);
      const pose = bone.appliedPose ?? bone;
      const actual = [pose.a, pose.b, pose.c, pose.d, pose.worldX, pose.worldY];
      actual.forEach((value, i) => {
        const delta = Math.abs(value - expected[i]);
        if (!Number.isFinite(value)) throw new Error(`Nonfinite runtime pose: ${name}`);
        if (i < 4) maxMatrix = Math.max(maxMatrix, delta);
        else if (delta > maxPosition) { maxPosition = delta; worst = `${sample.skin}/${sample.animation}/${sample.time}/${name}/${i}`; }
        compared++;
      });
    }
    for (const geometry of sample.geometry ?? []) {
      const slot = skeleton.findSlot(geometry.slot);
      const attachment = skeleton.getAttachment(slot.data.index, geometry.name);
      if (!attachment || attachment.worldVerticesLength !== geometry.points.length) throw new Error(`Missing geometry: ${geometry.name}`);
      const vertices = new Float32Array(attachment.worldVerticesLength);
      if (runtime.MixFrom) attachment.computeWorldVertices(skeleton, slot, 0, vertices.length, vertices, 0, 2);
      else attachment.computeWorldVertices(slot, 0, vertices.length, vertices, 0, 2);
      vertices.forEach((value, i) => {
        if (!Number.isFinite(value)) throw new Error(`Nonfinite geometry: ${geometry.name}`);
        maxVertex = Math.max(maxVertex, Math.abs(value - geometry.points[i]));
        compared++;
      });
    }
  }
  const runtimeVersion = JSON.parse(readFileSync(resolve(dirname(modulePath), '../package.json'))).version;
  report.push({ version, runtimeVersion, format, samples: samples.length, compared, maxPosition, maxMatrix, maxVertex, worst });
}
writeFileSync(resolve(evidence, `official-runtime-${version}.json`), JSON.stringify(report, null, 2));
console.log(JSON.stringify(report, null, 2));
if (report.some(r => r.maxPosition > 1 || r.maxMatrix > .05 || r.maxVertex > 1)) process.exitCode = 1;
