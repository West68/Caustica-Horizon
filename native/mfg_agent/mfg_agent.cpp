#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <jni.h>
#include <filesystem>
#include <fstream>
#include <string>
namespace { HMODULE asi; }
// No loading or hook setup in our DllMain. The JVM invokes Agent_OnLoad outside loader lock.
static jint loadCore(const char* options) {
    try {
        if(!options||!*options)return JNI_ERR;
        auto requested=std::filesystem::u8path(options);
        if(!requested.is_absolute())return JNI_ERR;
        auto dir=std::filesystem::canonical(requested);
        if(GetModuleHandleW(L"RTXMFG.dll")||GetModuleHandleW(L"RTX40MFGCore.dll"))return JNI_ERR;
        std::ofstream log(dir/("agent-"+std::to_string(GetCurrentProcessId())+".log"));
        auto config=dir/L"RTX40MFG-Universal.json", status=dir/L"RTX40MFG-Universal.status.json";
        SetEnvironmentVariableW(L"RTX_MFG_CONFIG_PATH",config.c_str());
        SetEnvironmentVariableW(L"RTX_MFG_STATUS_PATH",status.c_str());
        // Inspection of this pinned ASI shows DllMain forwards process attach to MfgUnlockCoreLoaded;
        // no InitializeASI export exists. DLL_LOAD_DIR resolves its adjacent Core without PATH edits.
        asi=LoadLibraryExW((dir/L"RTX40MFG.asi").c_str(),nullptr,
            LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR|LOAD_LIBRARY_SEARCH_DEFAULT_DIRS);
        log<<"pid="<<GetCurrentProcessId()<<" asi="<<(void*)asi<<" error="<<(asi?0:GetLastError())<<std::endl;
        auto core=GetModuleHandleW(L"RTX40MFGCore.dll");log<<"core="<<(void*)core<<std::endl;
        if(!asi||!core)return JNI_ERR;
        wchar_t corePath[32768];if(!GetModuleFileNameW(core,corePath,32768))return JNI_ERR;
        if(!std::filesystem::equivalent(std::filesystem::path(corePath),dir/L"RTX40MFGCore.dll"))return JNI_ERR;
        log<<"verified adjacent Core loaded; hooks retained until process exit"<<std::endl;
        SetEnvironmentVariableW(L"CAUSTICA_MFG_AGENT_ACTIVE",L"1");
        return JNI_OK;
    } catch(...) { return JNI_ERR; }
}
extern "C" JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM*,char* options,void*) { return loadCore(options); }
// Fabric preLaunch uses the same initialization, before Caustica/Vulkan/Streamline classes initialize.
extern "C" __declspec(dllexport) int CausticaMfgBootstrap(const char* directory) { return loadCore(directory); }
// Deliberately no FreeLibrary: third-party hooks own process-lifetime code pointers.
extern "C" JNIEXPORT void JNICALL Agent_OnUnload(JavaVM*) {}
