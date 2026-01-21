#include <jni.h>
#include <stdlib.h>

#include "lame.h"

static jfieldID get_handle_field(JNIEnv *env, jobject thiz) {
    static jfieldID handle_field = NULL;
    if (handle_field == NULL) {
        jclass clazz = (*env)->GetObjectClass(env, thiz);
        handle_field = (*env)->GetFieldID(env, clazz, "handle", "J");
        (*env)->DeleteLocalRef(env, clazz);
    }
    return handle_field;
}

static lame_t get_handle(JNIEnv *env, jobject thiz) {
    jfieldID handle_field = get_handle_field(env, thiz);
    if (handle_field == NULL) {
        return NULL;
    }
    jlong handle = (*env)->GetLongField(env, thiz, handle_field);
    return (lame_t)(intptr_t)handle;
}

static void set_handle(JNIEnv *env, jobject thiz, lame_t handle) {
    jfieldID handle_field = get_handle_field(env, thiz);
    if (handle_field == NULL) {
        return;
    }
    (*env)->SetLongField(env, thiz, handle_field, (jlong)(intptr_t)handle);
}

JNIEXPORT void JNICALL
Java_com_github_axet_lamejni_Lame_open(JNIEnv *env, jobject thiz, jint channels,
                                      jint sample_rate, jint bit_rate,
                                      jint quality) {
    lame_t existing = get_handle(env, thiz);
    if (existing != NULL) {
        lame_close(existing);
        set_handle(env, thiz, NULL);
    }

    lame_t gfp = lame_init();
    if (gfp == NULL) {
        return;
    }

    lame_set_num_channels(gfp, channels);
    lame_set_in_samplerate(gfp, sample_rate);
    lame_set_out_samplerate(gfp, sample_rate);
    lame_set_brate(gfp, bit_rate);
    lame_set_quality(gfp, quality);
    lame_set_mode(gfp, channels == 1 ? MONO : STEREO);
    lame_set_VBR(gfp, vbr_off);

    if (lame_init_params(gfp) < 0) {
        lame_close(gfp);
        return;
    }

    set_handle(env, thiz, gfp);
}

JNIEXPORT jbyteArray JNICALL
Java_com_github_axet_lamejni_Lame_encode(JNIEnv *env, jobject thiz,
                                        jshortArray pcm, jint offset,
                                        jint length) {
    if (pcm == NULL || length <= 0) {
        return NULL;
    }

    lame_t gfp = get_handle(env, thiz);
    if (gfp == NULL) {
        return NULL;
    }

    jsize array_len = (*env)->GetArrayLength(env, pcm);
    if (offset < 0 || length < 0 || offset + length > array_len) {
        return NULL;
    }

    jshort *input = (*env)->GetShortArrayElements(env, pcm, NULL);
    if (input == NULL) {
        return NULL;
    }

    jshort *pcm_ptr = input + offset;
    int mp3buf_size = (int)(1.25 * length + 7200);
    unsigned char *mp3buf = (unsigned char *)malloc((size_t)mp3buf_size);
    if (mp3buf == NULL) {
        (*env)->ReleaseShortArrayElements(env, pcm, input, JNI_ABORT);
        return NULL;
    }

    int encoded = lame_encode_buffer(gfp, pcm_ptr, NULL, length, mp3buf, mp3buf_size);

    (*env)->ReleaseShortArrayElements(env, pcm, input, JNI_ABORT);

    if (encoded <= 0) {
        free(mp3buf);
        return NULL;
    }

    jbyteArray output = (*env)->NewByteArray(env, encoded);
    if (output != NULL) {
        (*env)->SetByteArrayRegion(env, output, 0, encoded, (jbyte *)mp3buf);
    }
    free(mp3buf);
    return output;
}

JNIEXPORT jbyteArray JNICALL
Java_com_github_axet_lamejni_Lame_encode_1float(JNIEnv *env, jobject thiz,
                                               jfloatArray pcm, jint offset,
                                               jint length) {
    if (pcm == NULL || length <= 0) {
        return NULL;
    }

    lame_t gfp = get_handle(env, thiz);
    if (gfp == NULL) {
        return NULL;
    }

    jsize array_len = (*env)->GetArrayLength(env, pcm);
    if (offset < 0 || length < 0 || offset + length > array_len) {
        return NULL;
    }

    jfloat *input = (*env)->GetFloatArrayElements(env, pcm, NULL);
    if (input == NULL) {
        return NULL;
    }

    jfloat *pcm_ptr = input + offset;
    int mp3buf_size = (int)(1.25 * length + 7200);
    unsigned char *mp3buf = (unsigned char *)malloc((size_t)mp3buf_size);
    if (mp3buf == NULL) {
        (*env)->ReleaseFloatArrayElements(env, pcm, input, JNI_ABORT);
        return NULL;
    }

    int encoded = lame_encode_buffer_ieee_float(gfp, pcm_ptr, NULL, length, mp3buf, mp3buf_size);

    (*env)->ReleaseFloatArrayElements(env, pcm, input, JNI_ABORT);

    if (encoded <= 0) {
        free(mp3buf);
        return NULL;
    }

    jbyteArray output = (*env)->NewByteArray(env, encoded);
    if (output != NULL) {
        (*env)->SetByteArrayRegion(env, output, 0, encoded, (jbyte *)mp3buf);
    }
    free(mp3buf);
    return output;
}

JNIEXPORT jbyteArray JNICALL
Java_com_github_axet_lamejni_Lame_close(JNIEnv *env, jobject thiz) {
    lame_t gfp = get_handle(env, thiz);
    if (gfp == NULL) {
        return (*env)->NewByteArray(env, 0);
    }

    unsigned char mp3buf[7200];
    int encoded = lame_encode_flush(gfp, mp3buf, (int)sizeof(mp3buf));

    lame_close(gfp);
    set_handle(env, thiz, NULL);

    if (encoded < 0) {
        encoded = 0;
    }

    jbyteArray output = (*env)->NewByteArray(env, encoded);
    if (encoded > 0 && output != NULL) {
        (*env)->SetByteArrayRegion(env, output, 0, encoded, (jbyte *)mp3buf);
    }
    return output;
}
