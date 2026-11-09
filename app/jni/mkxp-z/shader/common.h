#ifdef GLSLES

#ifdef FRAGMENT_SHADER
/* Only the fragment shader has no default float precision.
 * rejuv-dual: use highp where available. On Adreno, mediump is real FP16,
 * so texture coordinates into a large tile atlas (up to 16384px) were off
 * by up to 8 texels, smearing tiles into flat colors or transparency. */
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
#endif

#else

/* Desktop GLSL doesn't know about these */
#define highp
#define mediump
#define lowp

#endif
