const path = require('path');
const HtmlWebpackPlugin = require('html-webpack-plugin');
const MiniCssExtractPlugin = require('mini-css-extract-plugin');
const CopyPlugin = require('copy-webpack-plugin');

module.exports = (env, argv) => {
  const devMode = argv.mode !== 'production';
  return {
    entry: {
      app: './src/ts/index.ts',
      // /p/{token}: a published note, without the app (see PublishedPageServlet).
      published: './src/ts/published.ts',
    },
    devtool: devMode ? 'inline-source-map' : 'source-map',
    module: {
      rules: [
        {
          test: /\.ts$/,
          include: path.resolve(__dirname, 'src/ts'),
          use: 'ts-loader',
          exclude: /node_modules/,
        },
        {
          test: /\.s[ac]ss$/i,
          include: path.resolve(__dirname, 'src/css'),
          use: [
            // A file in both modes: the CSP refuses the <style> elements style-loader would inject.
            MiniCssExtractPlugin.loader,
            { loader: 'css-loader', options: { sourceMap: true } },
            { loader: 'sass-loader', options: { sourceMap: true } },
          ],
        },
      ],
    },
    resolve: {
      extensions: ['.ts', '.js'],
    },
    output: {
      filename: 'bundle-[contenthash:6].js',
      path: path.resolve(__dirname, 'dist'),
      // Absolute: published.html is served at /p/{token}.
      publicPath: '/',
      clean: true,
    },
    plugins: [
      new HtmlWebpackPlugin({
        template: './src/html/index.html',
        chunks: ['app'],
      }),
      new HtmlWebpackPlugin({
        template: './src/html/published.html',
        filename: 'published.html',
        chunks: ['published'],
      }),
      new MiniCssExtractPlugin({
        filename: 'bundle-[contenthash:6].css',
      }),
      // Referenced by absolute URLs from index.html, so webpack wouldn't emit them on its own.
      new CopyPlugin({
        patterns: [
          { from: 'src/icons', to: 'icons', globOptions: { ignore: ['**/.DS_Store'] } },
          { from: 'src/licenses', to: 'licenses' },
          { from: 'src/pwa/manifest.webmanifest', to: 'manifest.webmanifest' },
          { from: 'src/pwa/sw.js', to: 'sw.js' },
          { from: 'src/compat/compat.js', to: 'compat.js' },
        ],
      }),
    ],
  };
};
