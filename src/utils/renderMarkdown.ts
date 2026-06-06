import chalk from 'chalk';

export function renderMarkdown(text: string): string {
  let result = text;

  result = result.replace(/^### (.+)$/gm, (_, m) => chalk.cyan.bold(m));
  result = result.replace(/^## (.+)$/gm, (_, m) => chalk.yellow.bold(m));
  result = result.replace(/^# (.+)$/gm, (_, m) => chalk.white.bold.underline(m));
  result = result.replace(/\*\*(.+?)\*\*/g, (_, m) => chalk.bold(m));
  result = result.replace(/\*(.+?)\*/g, (_, m) => chalk.italic(m));
  result = result.replace(/`([^`]+)`/g, (_, m) => chalk.bgGray.white(` ${m} `));
  result = result.replace(/```[\w]*\n([\s\S]+?)```/g, (_, code) =>
    code.split('\n')
      .map((l: string) => chalk.gray('│ ') + chalk.green(l))
      .join('\n')
  );
  result = result.replace(/^[-*] (.+)$/gm, (_, m) => chalk.cyan('  ◆ ') + m);
  result = result.replace(/^(\d+)\. (.+)$/gm, (_, n, m) => chalk.yellow(`  ${n}. `) + m);
  result = result.replace(/^---+$/gm, chalk.gray('─'.repeat(60)));

  return result;
}
