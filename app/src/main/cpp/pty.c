#define _GNU_SOURCE
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

static void fail(JNIEnv *env,const char *message){(*env)->ThrowNew(env,(*env)->FindClass(env,"java/io/IOException"),message);}
static char **strings(JNIEnv *env,jobjectArray values){jsize size=(*env)->GetArrayLength(env,values);char **result=calloc((size_t)size+1,sizeof(char*));if(!result)return NULL;for(jsize i=0;i<size;i++){jstring value=(jstring)(*env)->GetObjectArrayElement(env,values,i);const char *utf=(*env)->GetStringUTFChars(env,value,NULL);if(!utf){for(jsize k=0;k<i;k++)free(result[k]);free(result);return NULL;}result[i]=strdup(utf);(*env)->ReleaseStringUTFChars(env,value,utf);(*env)->DeleteLocalRef(env,value);if(!result[i]){for(jsize k=0;k<i;k++)free(result[k]);free(result);return NULL;}}return result;}
static void release(char **values){if(values){for(int i=0;values[i];i++)free(values[i]);free(values);}}
JNIEXPORT jintArray JNICALL Java_dev_wagent_PtySession_spawn(JNIEnv *env,jclass type,jobjectArray args,jobjectArray environment,jstring cwd,jint cols,jint rows){
    (void)type;char **argv=strings(env,args),**envp=strings(env,environment);const char *directory=(*env)->GetStringUTFChars(env,cwd,NULL);if(!argv||!envp||!directory){release(argv);release(envp);return NULL;}
    int master=posix_openpt(O_RDWR|O_NOCTTY|O_CLOEXEC),slave=-1;char name[128];if(master<0||grantpt(master)||unlockpt(master)||ptsname_r(master,name,sizeof(name))||(slave=open(name,O_RDWR|O_NOCTTY|O_CLOEXEC))<0){if(master>=0)close(master);release(argv);release(envp);(*env)->ReleaseStringUTFChars(env,cwd,directory);fail(env,"Не удалось открыть PTY Android");return NULL;}
    struct winsize size={.ws_row=(unsigned short)rows,.ws_col=(unsigned short)cols};ioctl(slave,TIOCSWINSZ,&size);
    // Prepare all allocations before fork; the child only uses async-signal-safe operations.
    pid_t pid=fork();if(pid==0){setsid();ioctl(slave,TIOCSCTTY,0);dup2(slave,STDIN_FILENO);dup2(slave,STDOUT_FILENO);dup2(slave,STDERR_FILENO);close(master);if(slave>2)close(slave);if(chdir(directory))_exit(126);execve(argv[0],argv,envp);const char msg[]="W Agent: запуск терминала не удался\r\n";write(STDERR_FILENO,msg,sizeof(msg)-1);_exit(127);}
    close(slave);release(argv);release(envp);(*env)->ReleaseStringUTFChars(env,cwd,directory);if(pid<0){close(master);fail(env,"Не удалось создать процесс терминала");return NULL;}
    jint values[2]={master,(jint)pid};jintArray result=(*env)->NewIntArray(env,2);if(result)(*env)->SetIntArrayRegion(env,result,0,2,values);else{kill(-pid,SIGKILL);kill(pid,SIGKILL);close(master);}return result;
}
JNIEXPORT void JNICALL Java_dev_wagent_PtySession_resizeNative(JNIEnv *env,jclass type,jint fd,jint cols,jint rows){(void)env;(void)type;struct winsize size={.ws_row=(unsigned short)rows,.ws_col=(unsigned short)cols};ioctl(fd,TIOCSWINSZ,&size);}
JNIEXPORT void JNICALL Java_dev_wagent_PtySession_killNative(JNIEnv *env,jclass type,jint pid){(void)env;(void)type;kill(-pid,SIGKILL);kill(pid,SIGKILL);}
JNIEXPORT jint JNICALL Java_dev_wagent_PtySession_waitNative(JNIEnv *env,jclass type,jint pid){(void)env;(void)type;int status;while(waitpid(pid,&status,0)<0){if(errno!=EINTR)return -1;}return WIFEXITED(status)?WEXITSTATUS(status):128+WTERMSIG(status);}
