#include "binding-util.h"

#include <SDL.h>
#include <jni.h>
#include <android/log.h>
#include <string>

RB_METHOD(androidDeviceInfo)
{
	RB_UNUSED_PARAM;

	VALUE hash = rb_hash_new();
	JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
	jclass cls = env->FindClass("android/os/Build");

	// Build.BRAND field
	jfieldID fIDBrand = env->GetStaticFieldID(cls, "BRAND", "Ljava/lang/String;");
	jstring strJBrand = (jstring)env->GetStaticObjectField(cls, fIDBrand);
	const char *strCBrand = env->GetStringUTFChars(strJBrand, 0);

	// Build.MANUFACTURER field
	jfieldID fIDManufacturer = env->GetStaticFieldID(cls, "MANUFACTURER", "Ljava/lang/String;");
	jstring strJManufacturer = (jstring)env->GetStaticObjectField(cls, fIDManufacturer);
	const char *strCManufacturer = env->GetStringUTFChars(strJManufacturer, 0);

	// Build.MODEL field
	jfieldID fIDModel = env->GetStaticFieldID(cls, "MODEL", "Ljava/lang/String;");
	jstring strJModel = (jstring)env->GetStaticObjectField(cls, fIDModel);
	const char *strCModel = env->GetStringUTFChars(strJModel, 0);

	// Build.PRODUCT field
	jfieldID fIDProduct = env->GetStaticFieldID(cls, "PRODUCT", "Ljava/lang/String;");
	jstring strJProduct = (jstring)env->GetStaticObjectField(cls, fIDProduct);
	const char *strCProduct = env->GetStringUTFChars(strJProduct, 0);

	rb_hash_aset(hash, ID2SYM(rb_intern("brand")), rb_str_new_cstr(strCBrand));
	rb_hash_aset(hash, ID2SYM(rb_intern("manufacturer")), rb_str_new_cstr(strCManufacturer));
	rb_hash_aset(hash, ID2SYM(rb_intern("model")), rb_str_new_cstr(strCModel));
	rb_hash_aset(hash, ID2SYM(rb_intern("product")), rb_str_new_cstr(strCProduct));

	env->ReleaseStringUTFChars(strJBrand, strCBrand);
	env->ReleaseStringUTFChars(strJManufacturer, strCManufacturer);
	env->ReleaseStringUTFChars(strJModel, strCModel);
	env->ReleaseStringUTFChars(strJProduct, strCProduct);
	env->DeleteLocalRef(strJBrand);
	env->DeleteLocalRef(strJManufacturer);
	env->DeleteLocalRef(strJModel);
	env->DeleteLocalRef(strJProduct);
	env->DeleteLocalRef(cls);

	return hash;
}

RB_METHOD(androidIsTablet)
{
	RB_UNUSED_PARAM;

	return rb_bool_new(SDL_IsTablet() == SDL_TRUE);
}

RB_METHOD(androidIsTV)
{
	RB_UNUSED_PARAM;

	return rb_bool_new(SDL_IsAndroidTV() == SDL_TRUE);
}

RB_METHOD(androidIsChromebook)
{
	RB_UNUSED_PARAM;

	return rb_bool_new(SDL_IsChromebook() == SDL_TRUE);
}

RB_METHOD(androidIsDeXMode)
{
	RB_UNUSED_PARAM;

	return rb_bool_new(SDL_IsDeXMode() == SDL_TRUE);
}

RB_METHOD(androidHasVibrator)
{
	RB_UNUSED_PARAM;

	JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
	jobject activity = (jobject)SDL_AndroidGetActivity();
	jclass cls = env->GetObjectClass(activity);

	jmethodID mID = env->GetStaticMethodID(cls, "hasVibrator", "()Z");
	SDL_assert(mID != 0);
	jboolean hasVibrator = (jboolean)env->CallStaticBooleanMethod(cls, mID);

	env->DeleteLocalRef(cls);
	env->DeleteLocalRef(activity);

	return rb_bool_new(hasVibrator);
}

RB_METHOD(androidVibrate)
{
	RB_UNUSED_PARAM;

	int duration;
	rb_get_args(argc, argv, "i", &duration RB_ARG_END);

	JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
	jobject activity = (jobject)SDL_AndroidGetActivity();
	jclass cls = env->GetObjectClass(activity);

	jmethodID mID = env->GetStaticMethodID(cls, "vibrate", "(I)V");
	SDL_assert(mID != 0);
	env->CallStaticVoidMethod(cls, mID, (jint)duration);

	env->DeleteLocalRef(cls);
	env->DeleteLocalRef(activity);

	return Qnil;
}

RB_METHOD(androidVibrateStop)
{
	RB_UNUSED_PARAM;

	JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
	jobject activity = (jobject)SDL_AndroidGetActivity();
	jclass cls = env->GetObjectClass(activity);

	jmethodID mID = env->GetStaticMethodID(cls, "vibrateStop", "()V");
	SDL_assert(mID != 0);
	env->CallStaticVoidMethod(cls, mID);

	env->DeleteLocalRef(cls);
	env->DeleteLocalRef(activity);

	return Qnil;
}

RB_METHOD(androidInMultiWindow)
{
	RB_UNUSED_PARAM;

	JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
	jobject activity = (jobject)SDL_AndroidGetActivity();
	jclass cls = env->GetObjectClass(activity);

	jmethodID mID = env->GetStaticMethodID(cls, "inMultiWindow", "(Landroid/app/Activity;)Z");
	SDL_assert(mID != 0);
	jboolean winMode = (jboolean)env->CallStaticBooleanMethod(cls, mID, activity);

	env->DeleteLocalRef(cls);
	env->DeleteLocalRef(activity);

	return rb_bool_new(winMode);
}

/* ── rejuv-dual: Companion bridge ───────────────────────────────────────────
 * Ruby <-> Java bridge for the second-screen companion UI.
 * All payloads are ASCII-only JSON strings (the Ruby side escapes non-ASCII
 * as \uXXXX), so JNI's modified UTF-8 is never an issue.
 */

static jclass companionActivityClass(JNIEnv *env, jobject *activityOut)
{
	jobject activity = (jobject)SDL_AndroidGetActivity();
	jclass cls = env->GetObjectClass(activity);
	if (activityOut)
		*activityOut = activity;
	else
		env->DeleteLocalRef(activity);
	return cls;
}

static void companionClearException(JNIEnv *env)
{
	if (env->ExceptionCheck()) {
		env->ExceptionDescribe();
		env->ExceptionClear();
	}
}

RB_METHOD(companionPush)
{
	RB_UNUSED_PARAM;

	const char *payload;
	rb_get_args(argc, argv, "z", &payload RB_ARG_END);

	JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
	jclass cls = companionActivityClass(env, NULL);

	jmethodID mID = env->GetStaticMethodID(cls, "companionPush", "(Ljava/lang/String;)V");
	if (mID) {
		jstring jstr = env->NewStringUTF(payload);
		env->CallStaticVoidMethod(cls, mID, jstr);
		env->DeleteLocalRef(jstr);
	}
	companionClearException(env);
	env->DeleteLocalRef(cls);

	return Qnil;
}

RB_METHOD(companionPoll)
{
	RB_UNUSED_PARAM;

	JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
	jclass cls = companionActivityClass(env, NULL);
	VALUE ret = Qnil;

	jmethodID mID = env->GetStaticMethodID(cls, "companionPoll", "()Ljava/lang/String;");
	if (mID) {
		jstring jstr = (jstring)env->CallStaticObjectMethod(cls, mID);
		if (jstr) {
			const char *c = env->GetStringUTFChars(jstr, 0);
			ret = rb_str_new_cstr(c);
			env->ReleaseStringUTFChars(jstr, c);
			env->DeleteLocalRef(jstr);
		}
	}
	companionClearException(env);
	env->DeleteLocalRef(cls);

	return ret;
}

RB_METHOD(companionActive)
{
	RB_UNUSED_PARAM;

	JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
	jclass cls = companionActivityClass(env, NULL);
	jboolean active = JNI_FALSE;

	jmethodID mID = env->GetStaticMethodID(cls, "companionActive", "()Z");
	if (mID)
		active = env->CallStaticBooleanMethod(cls, mID);
	companionClearException(env);
	env->DeleteLocalRef(cls);

	return rb_bool_new(active);
}

RB_METHOD(companionLog)
{
	RB_UNUSED_PARAM;

	const char *msg;
	rb_get_args(argc, argv, "z", &msg RB_ARG_END);
	__android_log_print(ANDROID_LOG_INFO, "rejuv-dual[ruby]", "%s", msg);

	return Qnil;
}

/* Absolute path of the bundled companion.rb (extracted by MainActivity),
 * or an empty string if unavailable. Called before game scripts run. */
std::string androidCompanionScriptPath()
{
	std::string path;
	JNIEnv *env = (JNIEnv *)SDL_AndroidGetJNIEnv();
	jclass cls = companionActivityClass(env, NULL);

	jmethodID mID = env->GetStaticMethodID(cls, "companionScriptPath", "()Ljava/lang/String;");
	if (mID) {
		jstring jstr = (jstring)env->CallStaticObjectMethod(cls, mID);
		if (jstr) {
			const char *c = env->GetStringUTFChars(jstr, 0);
			path = c;
			env->ReleaseStringUTFChars(jstr, c);
			env->DeleteLocalRef(jstr);
		}
	}
	companionClearException(env);
	env->DeleteLocalRef(cls);

	return path;
}

void androidBindingInit()
{
	VALUE module = rb_define_module("Android");

	// Android version/device info
	rb_const_set(module, rb_intern("SDK_VERSION"), INT2NUM(SDL_GetAndroidSDKVersion()));
	_rb_define_module_function(module, "device", androidDeviceInfo);
	_rb_define_module_function(module, "is_tablet?", androidIsTablet);
	_rb_define_module_function(module, "is_tv?", androidIsTV);
	_rb_define_module_function(module, "is_chromebook?", androidIsChromebook);
	_rb_define_module_function(module, "is_dex_mode?", androidIsDeXMode);

	// Vibration
	_rb_define_module_function(module, "has_vibrator?", androidHasVibrator);
	_rb_define_module_function(module, "vibrate", androidVibrate);
	_rb_define_module_function(module, "vibrate_stop", androidVibrateStop);

	// Multi-window (Android 7.0+)
	_rb_define_module_function(module, "in_multiwindow?", androidInMultiWindow);

	// rejuv-dual companion bridge
	VALUE companion = rb_define_module("Companion");
	_rb_define_module_function(companion, "push", companionPush);
	_rb_define_module_function(companion, "poll", companionPoll);
	_rb_define_module_function(companion, "active?", companionActive);
	_rb_define_module_function(companion, "log", companionLog);
}
