use jni::objects::{JClass, JFloatArray, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use whisper_rs::{FullParams, SamplingStrategy, WhisperContext, WhisperContextParameters};

/// Loads the model, transcribes 16 kHz mono f32 PCM, then frees everything so idle RAM stays near zero.
pub fn transcribe(model: &str, pcm: &[f32]) -> Result<String, String> {
    let ctx = WhisperContext::new_with_params(model, WhisperContextParameters::default())
        .map_err(|e| e.to_string())?;
    let mut state = ctx.create_state().map_err(|e| e.to_string())?;
    let mut p = FullParams::new(SamplingStrategy::Greedy { best_of: 1 });
    p.set_n_threads(4);
    p.set_language(Some("en"));
    p.set_no_context(true);
    p.set_suppress_blank(true);
    p.set_suppress_nst(true);
    p.set_print_progress(false);
    p.set_print_realtime(false);
    p.set_print_special(false);
    p.set_print_timestamps(false);
    state.full(p, pcm).map_err(|e| e.to_string())?;
    let text: Vec<String> = state
        .as_iter()
        .filter_map(|s| s.to_str_lossy().ok().map(|t| t.trim().to_string()))
        .filter(|t| !t.is_empty() && !t.starts_with('['))
        .collect();
    drop(state);
    drop(ctx);
    // App processes keep freed pages cached, so hand them back to the OS. -104 is M_PURGE_ALL, -101 is M_PURGE.
    unsafe {
        if mallopt(-104, 0) == 0 {
            mallopt(-101, 0);
        }
    }
    Ok(text.join(" "))
}

extern "C" {
    fn mallopt(param: i32, value: i32) -> i32;
}

#[no_mangle]
pub extern "system" fn Java_dev_wispr_Svc_transcribe<'l>(
    mut env: JNIEnv<'l>,
    _: JClass<'l>,
    model: JString<'l>,
    pcm: JFloatArray<'l>,
) -> jstring {
    let model: String = match env.get_string(&model) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let len = env.get_array_length(&pcm).unwrap_or(0) as usize;
    let mut buf = vec![0f32; len];
    if env.get_float_array_region(&pcm, 0, &mut buf).is_err() {
        return std::ptr::null_mut();
    }
    match transcribe(&model, &buf) {
        Ok(t) => env.new_string(t).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut()),
        Err(e) => {
            let _ = env.throw_new("java/lang/RuntimeException", e);
            std::ptr::null_mut()
        }
    }
}

#[cfg(test)]
mod tests {
    // WISPR_MODEL=../models/ggml-base.en-q5_1.bin WISPR_WAV=../tmp/jfk.wav cargo test --release
    #[test]
    fn transcribes_jfk() {
        let wav = std::fs::read(std::env::var("WISPR_WAV").unwrap()).unwrap();
        let pcm: Vec<f32> = wav[44..]
            .chunks_exact(2)
            .map(|b| i16::from_le_bytes([b[0], b[1]]) as f32 / 32768.0)
            .collect();
        let t = super::transcribe(&std::env::var("WISPR_MODEL").unwrap(), &pcm).unwrap();
        println!("{t}");
        assert!(t.to_lowercase().contains("ask not what your country can do for you"));
    }
}

