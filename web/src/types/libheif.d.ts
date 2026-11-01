// libheif-js ships its WebAssembly bundle as an untyped ES module; core/media.ts describes the part it uses.
declare module 'libheif-js/libheif-wasm/libheif-bundle.mjs' {
  const libheif: unknown;
  export default libheif;
}
