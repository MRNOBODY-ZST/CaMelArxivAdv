<template>
  <div class="mx-auto max-w-xl rounded-xl bg-white p-8 text-center shadow-sm ring-1 ring-slate-200">
    <p class="text-sm font-semibold text-brand-600">
      403
    </p>
    <h1 class="mt-2 text-2xl font-semibold text-slate-950">
      没有访问权限
    </h1>
    <p class="mt-3 text-sm/6 text-slate-500">
      此页面需要额外权限。若职责刚刚发生变化，请重新登录或联系管理员。
    </p>
    <RouterLink
      class="mt-6 inline-flex min-h-11 items-center rounded-md bg-brand-500 px-4 text-sm font-semibold text-white hover:bg-brand-600"
      to="/"
    >
      返回数据总览
    </RouterLink>
  </div>
</template>
